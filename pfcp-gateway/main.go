package main

import (
	// "context"

	"context"
	"flag"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync"
	"time"

	pb "github.com/briansp8210/sslbupf/pfcp-gateway/proto"
	"github.com/wmnsk/go-pfcp/ie"
	"github.com/wmnsk/go-pfcp/message"
	"google.golang.org/grpc"
	"gopkg.in/yaml.v2"

	v1 "k8s.io/api/core/v1"
	"k8s.io/client-go/informers"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/cache"
)

const (
	SB_CHANNEL_SIZE = 16
)

type pfcpgwConfig struct {
	CoreNetwork     string `yaml:"coreNetwork"`
	SmfIp           string `yaml:"smfIp"`
	LbcGrpcEndpoint string `yaml:"lbcGrpcEndpoint"`
	UpfServices     []struct {
		Ipv4  string `yaml:"ipv4"`
		IsPsa bool   `yaml:"isPsa"`
		DevId string `yaml:"devId"`
	} `yaml:"upfServices"`
}

type sessionMetaData struct {
	sessEstReq *message.Header
	fseid      *ie.FSEIDFields
	fteids     []*ie.FTEIDFields
	ueIp       *ie.UEIPAddressFields
}

type PfcpGateway struct {
	DataStore

	mu          sync.RWMutex
	smfIp       string
	upfServices map[string]*upfService

	// Assuming that a SMF use the same sequence number space for all UPFs.
	// TODO: Survey TS 29.244 for related specifications.
	processingSeqNums map[uint32]bool
	// The sessionMetaData is significant only when the message type is PFCP session related.
	// The sessionMetaData should be sent to Load Balancer Agent in NorthboundOutputHandler of response messages.
	seqNumToSessionMetaData map[uint32]*sessionMetaData

	// Used for PFCP Heartbeat proxy
	recoveryTs *ie.IE

	lbagentClient pb.LoadBalancerAgentClient
	k8sClient     *kubernetes.Clientset
}

type upfService struct {
	*pb.UpfService
	nbConn  *net.UDPConn
	sbInfos map[string]*sbInfo
}

// `ch` is in charge of sending "Synchronus" packets (E.g. Association, Session).
// Only after the response of previous packet is received, can we fetch next packet
// from `ch` and send.
//
// `conn` is the actual connection to the UPF instance. "Asynchronus"
// packets (E.g. Heartbeat response) can be sent directly ASAP without
// blocking in `ch`.
type sbInfo struct {
	ch   chan []byte
	conn *net.UDPConn
}

func initPfcpgwConfig(configPath string) pfcpgwConfig {
	content, err := ioutil.ReadFile(configPath)
	if err != nil {
		log.Fatal(err)
	}

	config := pfcpgwConfig{}
	if err = yaml.Unmarshal(content, &config); err != nil {
		log.Fatal(err)
	}
	return config
}

func NewPfcpGateway(configPath string, ds DataStore) *PfcpGateway {
	config := initPfcpgwConfig(configPath)
	pfcpGateway := &PfcpGateway{
		DataStore:               ds,
		smfIp:                   config.SmfIp,
		upfServices:             make(map[string]*upfService),
		processingSeqNums:       make(map[uint32]bool),
		seqNumToSessionMetaData: make(map[uint32]*sessionMetaData),
		recoveryTs:              ie.NewRecoveryTimeStamp(time.Now()),
	}

	log.Println("UPF Services:")
	for _, service := range config.UpfServices {
		pfcpGateway.upfServices[service.Ipv4] = &upfService{
			UpfService: &pb.UpfService{
				Ipv4:  service.Ipv4,
				IsPsa: service.IsPsa,
				DevId: service.DevId,
			},
		}
		log.Printf("Ipv4: %v\n", service.Ipv4)
		log.Printf("IsPsa: %v\n", service.IsPsa)
		log.Printf("DevId: %v\n", service.DevId)
		log.Println("----------------")
	}

	k8sConfig, err := rest.InClusterConfig()
	if err != nil {
		log.Fatal(err)
	}
	pfcpGateway.k8sClient, err = kubernetes.NewForConfig(k8sConfig)
	if err != nil {
		log.Fatal(err)
	}

	conn, err := grpc.Dial(config.LbcGrpcEndpoint, grpc.WithInsecure())
	if err != nil {
		log.Fatal(err)
	}
	pfcpGateway.lbagentClient = pb.NewLoadBalancerAgentClient(conn)
	log.Println("Connected to LoadBalancerAgent")

	req := &pb.InitializeRequest{UpfServices: make([]*pb.UpfService, 0, len(pfcpGateway.upfServices))}
	switch config.CoreNetwork {
	case "free5gc":
		req.CoreNetwork = pb.InitializeRequest_CORE_NETWORK_FREE5GC
	case "open5gs":
		req.CoreNetwork = pb.InitializeRequest_CORE_NETWORK_OPEN5GS
	default:
		log.Fatalf("Unsupported core network: %s", config.CoreNetwork)
	}
	log.Printf("Core network: %s\n", config.CoreNetwork)
	for _, upfService := range pfcpGateway.upfServices {
		req.UpfServices = append(req.UpfServices, upfService.UpfService)
	}
	pfcpGateway.lbagentClient.Initialize(context.Background(), req)

	return pfcpGateway
}

func (pg *PfcpGateway) initUpfInformer() {
	// Initialize pod informer which monitors UPF related events
	factory := informers.NewSharedInformerFactory(pg.k8sClient, time.Hour*24)
	podInformer := factory.Core().V1().Pods()
	podInformer.Informer().AddEventHandler(
		cache.ResourceEventHandlerFuncs{
			AddFunc: func(obj interface{}) {
				pod := obj.(*v1.Pod)
				go pg.upfScaleOutHandler(pod)
			},
			UpdateFunc: func(old, new interface{}) {
				oldpod := old.(*v1.Pod)
				newpod := new.(*v1.Pod)
				if oldpod.DeletionTimestamp == nil && newpod.DeletionTimestamp != nil {
					go pg.upfDeleteHandler(newpod)
				} else {
					go pg.upfScaleOutHandler(newpod)
				}
			},
		},
	)
	stop := make(chan struct{})
	factory.Start(stop)
	if !cache.WaitForCacheSync(stop, podInformer.Informer().HasSynced) {
		log.Fatal("Timed out waiting for caches to sync")
	}
}

func (pg *PfcpGateway) upfScaleOutHandler(pod *v1.Pod) {
	if pod.Status.Phase != "Running" || pod.Status.PodIP == "" {
		return
	}
	labels := pod.GetLabels()
	if _, exist := labels["upf-vip"]; !exist {
		return
	}
	upfVip, upfDip := labels["upf-vip"], pod.Status.PodIP

	pg.mu.RLock()
	// If the pod is already existed, just ignore this update event.
	if _, exist := pg.upfServices[upfVip].sbInfos[upfDip]; exist {
		pg.mu.RUnlock()
		return
	}
	pg.mu.RUnlock()

	log.Printf("UPF [vip: %s, dip: %s] is UP\n", upfVip, upfDip)

	// Wait for UPF opening PFCP port.
	// TODO: Should use a more robust way here.
	time.Sleep(3 * time.Second)

	// Open a south-bound socket for this new UPF instance.
	// The purpose of using separate socket for each UPF is to reduce complexity, enable simple synchronous processing.
	laddr := &net.UDPAddr{IP: net.ParseIP(pg.smfIp)}
	raddr := &net.UDPAddr{IP: net.ParseIP(upfDip), Port: 8805}
	conn, err := net.DialUDP("udp", laddr, raddr)
	if err != nil {
		log.Fatal(err)
	}

	sbChan := make(chan []byte, SB_CHANNEL_SIZE)
	sem := make(chan interface{})
	go func() {
		for packet := range sbChan {
			// nil indicates the UPF instance have had the association and sessions informations.
			// This scenario should happen only ONCE.
			if packet == nil {
				pg.mu.Lock()
				pg.upfServices[upfVip].sbInfos[upfDip] = &sbInfo{ch: sbChan, conn: conn}
				pg.mu.Unlock()

				pg.notifyUpfAddEvent(pod.GetName(), upfVip, upfDip, pod.Status.HostIP)
				log.Printf("%s scale out completed", pod.GetName())
				continue
			}

			if _, err := conn.Write(packet); err != nil {
				log.Fatal(err)
			}
			<-sem // Wait for response packet.
		}
	}()

	go func() {
		for {
			buf := readUdp(conn)
			if buf == nil {
				break
			}
			if isSync := pg.inboundHandler(upfDip, upfVip, buf); isSync {
				sem <- true
			}
		}
	}()

	// Setup association with the new UPF.
	req := pg.readAssocSetupReq(upfVip)
	if req != nil {
		log.Printf("assoc req: %v", req)
		packet, err := req.Marshal()
		if err != nil {
			log.Fatal(err)
		}
		pfcpHandler := NewPfcpHandler(pg, upfVip, upfDip, packet)
		pfcpHandler.SouthboundOutputHandler(upfDip)
		log.Printf("======== [%3d] SMF --> UPF (%15s): type %v ========", req.Sequence(), upfDip, req.MessageType())
		sbChan <- packet

		// Send all session establishment/modification messages to the UPF to update its context, so that it would behave like its siblings.
		for _, reqs := range pg.readAllSessEstModReqs(upfVip) {
			for _, req := range reqs {
				packet, err := req.Marshal()
				if err != nil {
					log.Fatal(err)
				}
				pfcpHandler := NewPfcpHandler(pg, upfVip, upfDip, packet)
				pfcpHandler.SouthboundOutputHandler(upfDip)
				log.Printf("======== [%3d] SMF --> UPF (%15s): type %v ========", req.Sequence(), upfDip, req.MessageType())
				sbChan <- packet
			}
		}
	}
	sbChan <- nil
}

func (pg *PfcpGateway) upfDeleteHandler(pod *v1.Pod) {
	labels := pod.GetLabels()
	if _, exist := labels["upf-vip"]; !exist {
		return
	}
	upfVip, upfDip := labels["upf-vip"], pod.Status.PodIP
	log.Printf("UPF [vip: %s, dip: %s] is going to be DOWN\n", upfVip, upfDip)

	pg.notifyUpfDeleteEvent(upfVip, upfDip)

	pg.mu.Lock()
	pg.upfServices[upfVip].sbInfos[upfDip].conn.Close()
	close(pg.upfServices[upfVip].sbInfos[upfDip].ch)
	delete(pg.upfServices[upfVip].sbInfos, upfDip)
	pg.mu.Unlock()
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)

	configPtr := flag.String("config", "", "Configuration file path")
	flag.Parse()

	pfcpGateway := NewPfcpGateway(*configPtr, NewMemDataStore())
	pfcpGateway.StartProxyServer()
}

func (pg *PfcpGateway) StartProxyServer() {
	var wg sync.WaitGroup
	for upfVip := range pg.upfServices {
		// Add each UPF virtual IP to loopback interface.
		if err := exec.Command("ip", strings.Split(fmt.Sprintf("a add %s/32 dev lo", upfVip), " ")...).Run(); err != nil {
			log.Fatal(err)
		}

		wg.Add(1)
		// Open north-bound socket for `upfVip`.
		go func(vip string) {
			pg.upfServices[vip] = &upfService{sbInfos: make(map[string]*sbInfo)}
			conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(vip), Port: 8805})
			if err != nil {
				log.Fatal(err)
			}
			defer conn.Close()
			pg.upfServices[vip].nbConn = conn
			wg.Done()

			for {
				buf := readUdp(conn)
				if buf == nil {
					break
				}
				go pg.outboundHandler(vip, buf)
			}
		}(upfVip)
	}

	wg.Wait()
	pg.initUpfInformer()

	// After opening all north-bound connections, open the readiness port.
	// This will terminate the PostStart hook and begin to start SMF.
	readinessPort := openReadinessPort()
	defer readinessPort.Close()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt)
	<-sigChan
}

func openReadinessPort() *net.UDPConn {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 65088})
	if err != nil {
		log.Fatal(err)
	}
	return conn
}

func (pg *PfcpGateway) outboundHandler(upfVip string, packet []byte) {
	pfcp, err := message.ParseHeader(packet)
	if err != nil {
		log.Fatal(err)
	}

	pg.mu.Lock()
	pg.processingSeqNums[pfcp.Sequence()] = true
	pg.seqNumToSessionMetaData[pfcp.Sequence()] = &sessionMetaData{}
	pg.mu.Unlock()
	pfcpHandler := NewPfcpHandler(pg, upfVip, "" /* upfDip not used here */, packet)
	if accept := pfcpHandler.SouthboundInputHandler(); !accept {
		pg.mu.Lock()
		delete(pg.processingSeqNums, pfcp.Sequence())
		delete(pg.seqNumToSessionMetaData, pfcp.Sequence())
		pg.mu.Unlock()
		return
	}

	pg.mu.RLock()
	defer pg.mu.RUnlock()
	for upfDip := range pg.upfServices[upfVip].sbInfos {
		go func(dip string) {
			pg.sendSyncPktToUpf(upfVip, dip, pfcpHandler.SouthboundOutputHandler(dip))
			log.Printf("======== [%3d] SMF --> UPF (%15s): type %v ========", pfcp.Sequence(), dip, pfcp.MessageType())
		}(upfDip)
	}
}

func (pg *PfcpGateway) inboundHandler(upfDip, upfVip string, packet []byte) bool {
	pfcp, err := message.ParseHeader(packet)
	if err != nil {
		log.Fatal(err)
	}

	log.Printf("======== [%3d] SMF <-- UPF (%15s): type %v ========", pfcp.Sequence(), upfDip, pfcp.MessageType())

	pfcpHandler := NewPfcpHandler(pg, upfVip, upfDip, packet)
	if accept := pfcpHandler.NorthboundInputHandler(); !accept {
		return false
	}

	pg.mu.Lock()
	if _, exist := pg.processingSeqNums[pfcp.Sequence()]; exist {
		delete(pg.processingSeqNums, pfcp.Sequence())
		pg.mu.Unlock()

		packet = pfcpHandler.NorthboundOutputHandler()
		pg.sendToSmf(upfVip, packet)
		pg.mu.Lock()
		delete(pg.seqNumToSessionMetaData, pfcp.Sequence())
		pg.mu.Unlock()
	} else {
		pg.mu.Unlock()
	}
	return true
}

func (pg *PfcpGateway) sendSyncPktToUpf(upfVip, upfDip string, packet []byte) {
	pg.mu.RLock()
	defer pg.mu.RUnlock()
	pg.upfServices[upfVip].sbInfos[upfDip].ch <- packet
}

func (pg *PfcpGateway) sendAsyncPktToUpf(upfVip, upfDip string, packet []byte) {
	pg.mu.RLock()
	defer pg.mu.RUnlock()
	if _, err := pg.upfServices[upfVip].sbInfos[upfDip].conn.Write(packet); err != nil {
		log.Fatal(err)
	}
}

func (pg *PfcpGateway) sendToSmf(upfVip string, packet []byte) {
	pg.mu.RLock()
	defer pg.mu.RUnlock()
	raddr := &net.UDPAddr{IP: net.ParseIP(pg.smfIp), Port: 8805}
	if _, err := pg.upfServices[upfVip].nbConn.WriteToUDP(packet, raddr); err != nil {
		log.Fatal(err)
	}
}

func readUdp(conn *net.UDPConn) []byte {
	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		log.Print(err)
		return nil
	}
	return buf[:n]
}

func (pg *PfcpGateway) updateSessionMetadata(seqNum uint32, sessEstReq *message.Header, fseid *ie.FSEIDFields, fteids []*ie.FTEIDFields, ueIp *ie.UEIPAddressFields) {
	pg.mu.Lock()
	defer pg.mu.Unlock()
	if sessEstReq != nil {
		pg.seqNumToSessionMetaData[seqNum].sessEstReq = sessEstReq
	}
	if fseid != nil {
		pg.seqNumToSessionMetaData[seqNum].fseid = fseid
	}
	pg.seqNumToSessionMetaData[seqNum].fteids = append(pg.seqNumToSessionMetaData[seqNum].fteids, fteids...)
	if ueIp != nil {
		pg.seqNumToSessionMetaData[seqNum].ueIp = ueIp
	}
}

func (pg *PfcpGateway) getSessEstReqFromSessionMetadata(seqNum uint32) *message.Header {
	pg.mu.RLock()
	defer pg.mu.RUnlock()
	return pg.seqNumToSessionMetaData[seqNum].sessEstReq
}

func (pg *PfcpGateway) notifyUpfAddEvent(name, upfVip, upfDip, nodeIp string) {
	req := &pb.AddUpfRequest{Name: name, UpfVip: upfVip, UpfDip: upfDip, NodeIp: nodeIp}
	if _, err := pg.lbagentClient.AddUpf(context.Background(), req); err != nil {
		log.Fatal(err)
	}
	log.Printf("Notify UPFLB on ADD_UPF event (%s, %s, %s)\n", upfVip, upfDip, nodeIp)
}

func (pg *PfcpGateway) notifyUpfDeleteEvent(upfVip, upfDip string) {
	req := &pb.DeleteUpfRequest{UpfVip: upfVip, UpfDip: upfDip}
	if _, err := pg.lbagentClient.DeleteUpf(context.Background(), req); err != nil {
		log.Fatal(err)
	}
	log.Printf("Notify UPFLB on DELETE_UPF event (%s, %s)\n", upfVip, upfDip)
}

func (pg *PfcpGateway) notifyPfcpSessionAddEvent(seqNum uint32) {
	pg.mu.RLock()
	md := pg.seqNumToSessionMetaData[seqNum]

	req := &pb.UpdatePfcpSessionRequest{}
	req.Fseid = &pb.FSeid{Seid: md.fseid.SEID, Ipv4: md.fseid.IPv4Address.String()}
	for _, fteid := range md.fteids {
		req.Fteids = append(req.Fteids, &pb.FTeid{Teid: fteid.TEID, Ipv4: fteid.IPv4Address.String()})
	}
	if md.ueIp != nil {
		req.UeIp = md.ueIp.IPv4Address.String()
	}
	pg.mu.RUnlock()

	if _, err := pg.lbagentClient.UpdatePfcpSession(context.Background(), req); err != nil {
		log.Fatal(err)
	}
	log.Printf("Notify UPFLB on UPDATE_PFCP_SESSION event (%v, %v)\n", req.Fseid.Ipv4, req.Fseid.Seid)
}

func (pg *PfcpGateway) notifyPfcpSessionDeleteEvent(fseid *ie.FSEIDFields, upfVip string) {
	req := &pb.DeletePfcpSessionRequest{Fseid: &pb.FSeid{Seid: fseid.SEID, Ipv4: fseid.IPv4Address.String()}}
	if _, err := pg.lbagentClient.DeletePfcpSession(context.Background(), req); err != nil {
		log.Fatal(err)
	}
	log.Printf("Notify UPFLB on DELETE_PFCP_SESSION event (%v, %v)\n", fseid.IPv4Address, fseid.SEID)
}
