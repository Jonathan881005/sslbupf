package nctu.winlab.ha5gup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import io.grpc.Server;
import io.grpc.ServerBuilder;

import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.PathService;

import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.InitializeRequest.CoreNetwork.CORE_NETWORK_FREE5GC_VALUE;
import static nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.InitializeRequest.CoreNetwork.CORE_NETWORK_OPEN5GS_VALUE;
import static nctu.winlab.ha5gup.UpfSelector.UpfSelectorCriteria.UPF_SELECTOR_CRITERIA_RECEIVING_THROUGHPUT;
import static org.onlab.util.Tools.get;
import static org.slf4j.LoggerFactory.getLogger;

@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class UpflbControl implements SomeInterface {
    protected static final Logger log = getLogger(UpflbControl.class);

    /** Some configurable property. */
    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EdgePortService edgePortService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PathService pathService;

    private ApplicationId appId;
    private Server grpcServer;
    private HttpServer httpServer;
    private UpfSelector upfSelector;
    private FlowRuleThroughputCollector flowRuleThroughputCollector;

    private final ArpProcessor arpProcessor = new ArpProcessor();
    private final K8sManager k8sManager = new K8sManager();
    private final HashMap<Ip4Address, UpfService> upfServices = new HashMap<Ip4Address, UpfService>();
    private final HashMap<Ip4Address, HashMap<Ip4Address, UpfInstance>> upfIpToUpfInstance = new HashMap<Ip4Address, HashMap<Ip4Address, UpfInstance>>();
    private final HashMap<FSeid, PfcpSession> fseidToPfcpSession = new HashMap<FSeid, PfcpSession>();
    private final HashMap<DeviceId, P4Manager> p4Managers = new HashMap<DeviceId, P4Manager>();
    private static final String APP_NAME = "nctu.winlab.ha5gup";

    @Activate
    protected void activate() {
        try {
            cfgService.registerProperties(getClass());
            appId = coreService.registerApplication(APP_NAME);
            flowRuleThroughputCollector = new FlowRuleThroughputCollector(appId, flowRuleService);

            // TODO: List of used P4 switches should be configurable.
            Map<DeviceId, MacAddress> devices = new HashMap<DeviceId, MacAddress>();
            devices.put(DeviceId.deviceId("device:tofino:invp4sw5"), MacAddress.valueOf("77:88:99:00:00:01"));
            for (Map.Entry<DeviceId, MacAddress> entry : devices.entrySet()) {
                DeviceId devId = entry.getKey();
                MacAddress mac = entry.getValue();
                Device dev = deviceService.getDevice(devId);
                if (dev == null) {
                    log.error("Device {} is not known", devId);
                }
                p4Managers.put(devId, new P4Manager(appId, dev, flowRuleService, mac));
            }

            packetService.addProcessor(arpProcessor, PacketProcessor.director(2));
            startHttpServer();
            startGrpcServer();
            log.info("Started", APP_NAME);
        } catch (Exception e) {
            log.error(e.toString());
        }
    }

    @Deactivate
    protected void deactivate() {
        try {
            httpServer.stop(0);
            grpcServer.shutdownNow();
            grpcServer.awaitTermination();
            log.info("gRPC server is terminated");

            flowRuleThroughputCollector.shutdownCollector();
            for (P4Manager manager : p4Managers.values()) {
                manager.removeFlowRuleListener();
            }
            flowRuleThroughputCollector.removeFlowRuleListener();

            cfgService.unregisterProperties(getClass(), false);
            flowRuleService.removeFlowRulesById(appId);
            packetService.removeProcessor(arpProcessor);
            log.info("Stopped", APP_NAME);
        } catch (Exception e) {
            log.error(e.toString());
        }
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    private void startHttpServer() {
        try {
            log.info("This is Thread #{}", Thread.currentThread().getId());
            httpServer = HttpServer.create(new InetSocketAddress(63000), 0);
            httpServer.createContext("/alertmanager/webhook", new AlertManagerWebhookHandler());
            httpServer.setExecutor(Executors.newCachedThreadPool());
            httpServer.start();
            log.info("HTTP server is running...");
        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    private void startGrpcServer() {
        try {
            grpcServer = ServerBuilder.forPort(61337).addService(new LoadBalancerAgentService(this)).build();
            grpcServer.start();
            log.info("gRPC server is running...");
        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    // TODO: Add a listner for node add/delete

    protected synchronized void initializeHandler(int coreNetwork, List<nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.UpfService> services) {
        switch (coreNetwork) {
            case CORE_NETWORK_FREE5GC_VALUE:
                upfSelector = new UpfSelector(UPF_SELECTOR_CRITERIA_RECEIVING_THROUGHPUT, upfIpToUpfInstance);
                break;
            case CORE_NETWORK_OPEN5GS_VALUE:
                upfSelector = new UpfSelector(UPF_SELECTOR_CRITERIA_RECEIVING_THROUGHPUT, upfIpToUpfInstance);
                break;
            default:
                log.info("Unsupported core network: {}", coreNetwork);
                break;
        }

        for (nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.UpfService service : services) {
            P4Manager manager = p4Managers.get(DeviceId.deviceId(service.getDevId()));
            UpfService upfService = new UpfService(Ip4Address.valueOf(service.getIpv4()), service.getIsPsa(), manager);
            upfServices.put(Ip4Address.valueOf(service.getIpv4()), upfService);
            manager.addUpfService(Ip4Address.valueOf(service.getIpv4()), upfService);
        }

        // Install P4 routing rules for inter-services GTP-U packets.
        ArrayList<P4Manager> managerList = new ArrayList<P4Manager>(p4Managers.values());
        for (int i = 0; i < managerList.size(); i++) {
            for (int j = i + 1; j < managerList.size(); j++) {
                P4Manager headManager = managerList.get(i);
                P4Manager tailManager = managerList.get(j);
                Path path = pathService.getPaths(headManager.getDevice().id(), tailManager.getDevice().id()).iterator().next();
                for (final Link link : path.links()) {
                    P4Manager srcManager = p4Managers.get(link.src().deviceId());
                    for (UpfService service : tailManager.getUpfServices().values()) {
                        srcManager.installIpRouteTableRule(service.getIpv4(), link.src().port().toLong(), tailManager.getVirtualMac());
                    }
                    P4Manager dstManager = p4Managers.get(link.dst().deviceId());
                    for (UpfService service : headManager.getUpfServices().values()) {
                        dstManager.installIpRouteTableRule(service.getIpv4(), link.dst().port().toLong(), tailManager.getVirtualMac());
                    }
                }
            }
        }
    }

    protected synchronized void addUpfHandler(String name, Ip4Address upfDip, Ip4Address upfVip, Ip4Address nodeIp) {
        if (upfIpToUpfInstance.get(upfVip) == null) {
            upfIpToUpfInstance.put(upfVip, new HashMap<Ip4Address, UpfInstance>());
        }
        // Host node = hostService.getHostsByIp(nodeIp).iterator().next();
        // Long nodePortNumber = node.location().port().toLong();
        // MacAddress nodeMac = node.mac();

        Long nodePortNumber = k8sManager.getNodePortNumber(nodeIp);
        MacAddress nodeMac = k8sManager.getNodeMacAddress(nodeIp);

        P4Manager p4Manager = upfServices.get(upfVip).getP4Manager();
        FlowRule snatRule = p4Manager.installUpfSnatTableRule(upfDip, upfVip);
        FlowRule routingRule = p4Manager.installIpRouteTableRule(upfDip, nodePortNumber, nodeMac);
        String hostIntfName = k8sManager.getHostIntfName(upfDip, nodeIp);
        upfIpToUpfInstance.get(upfVip).put(upfDip, new VnfUpfInstance(name, upfDip, nodePortNumber, nodeMac, nodeIp, snatRule, routingRule, hostIntfName));
    }

    // For simplicity, both ADD and UPDATE events will invoke this handler.
    protected synchronized void updatePfcpSessionHandler(FSeid fseid, ArrayList<FTeid> fteids, Ip4Address ueIp) {
        // Assuming that UPF uses single IP address for all GTP-U endpoints.
        Ip4Address upfVip = fseid.getIpv4();
        Ip4Address upfDip = null;
        PfcpSession session = fseidToPfcpSession.get(fseid);

        if (session == null) {
            upfDip = upfSelector.selectUpf(upfVip);
            session = new PfcpSession(fseid, upfDip, ueIp);
            upfIpToUpfInstance.get(upfVip).get(upfDip).addPfcpSession(session);
            fseidToPfcpSession.put(fseid, session);
        } else {
            // Select identical UPF instance for all GTP-U endpoints of a PDU session.
            upfDip = session.upfDip();
        }

        P4Manager p4Manager = upfServices.get(upfVip).getP4Manager();
        for (FTeid fteid : fteids) {
            if (!session.hasEndpoint(fteid)) {
                FlowRule dnatRule = p4Manager.installUpfDnatTableRule(fteid.getIpv4(), fteid.getTeid(), upfDip);
                session.addEndpoint(fteid, dnatRule);
            }
        }

        if (upfServices.get(upfVip).getIsPsa() && session.n6DlRoutingRule() == null) {
            UpfInstance upf = upfIpToUpfInstance.get(upfVip).get(upfDip);
            session.setN6DlRoutingRule(p4Manager.installIpRouteTableRule(ueIp, upf.nodePortNumber(), upf.nodeMac()));

            String cmd = String.format("ip route add %s/32 proto static dev %s", ueIp, upf.getHostIntfName());
            k8sManager.execHostRouteAgentCommand(upf.nodeIp(), cmd);
        }
    }

    protected synchronized void deleteUpfHandler(Ip4Address vip, Ip4Address dip) {
        UpfInstance originalUpf = upfIpToUpfInstance.get(vip).get(dip);
        upfIpToUpfInstance.get(vip).remove(dip);

        // TODO: The threshold should be configurable
        rebalance(vip, originalUpf.pfcpSessions().values(), originalUpf, 2.5);

        log.info("After migrating all sessions, terminate the old UPF and remove its SNAT and routing rule");
        k8sManager.execPodCommand(originalUpf.name(), "pkill upfd");
        flowRuleService.removeFlowRules(originalUpf.snatRule(), originalUpf.routingRule());
    }

    protected synchronized void overloadHandler(String name, Ip4Address vip, Ip4Address dip, double threshold) {
        double exceededThroughput = upfSelector.getUpfThroughput(name) - threshold;
        log.info("overloadHandler: start to rebalance {} (vip = {}, dip = {}), exceeding {} Gbps", name, vip, dip, exceededThroughput);

        try {
            if (!upfIpToUpfInstance.get(vip).containsKey(dip)) {
                // When AlertManagerWebhookHandler calling this function, the "dip" UPF may happen to
                // be terminated, which is handled by deleteUpfHandler.
                log.info("{} has been terminated!", dip);
                return;
            }

            UpfInstance originalUpf = upfIpToUpfInstance.get(vip).get(dip);
            if (originalUpf.pfcpSessions().size() <= 1) {
                log.info("{} has no enough session to migrate", dip);
                return;
            }

            log.info("PFCP session list:");
            log.info("================================");
            List<PfcpSession> sessions = new ArrayList<PfcpSession>();
            for (PfcpSession session : originalUpf.pfcpSessions().values()) {
                double throughput = 0;
                for (FlowRule flowRule : session.fteidToDnatRules().values()) {
                    throughput += flowRuleThroughputCollector.getThroughput(flowRule.id());
                }
                throughput += flowRuleThroughputCollector.getThroughput(session.n6DlRoutingRule().id());
                session.updateThroughput(throughput * 8 / 1000 / 1000 / 1000);
                sessions.add(session);
                log.info("FSEID {}, throughput = {} Gbps", session.fseid(), session.throughput());
            }
            log.info("================================");
            Collections.sort(sessions);

            // If the overload is caused by a single session, just leave it alone. There is no UPF can accommodate it.
            for (int i = 0; i < sessions.size() - 1; i++) {
                exceededThroughput -= sessions.get(i).throughput();
                if (exceededThroughput < 0) {
                    Collection<PfcpSession> subSessions = sessions.subList(0, i + 1);
                    rebalance(vip, subSessions, originalUpf, threshold);
                    break;
                }
            }
            log.info("overloadHandler: rebalance of {} (vip = {}, dip = {}) completed", name, vip, dip);
        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    private void rebalance(Ip4Address upfVip, Collection<PfcpSession> sessions, UpfInstance originalUpf, double threshold) {
        ExecutorService executorService = Executors.newCachedThreadPool();
        try {
            for (Map.Entry<UpfInstance, Collection<PfcpSession>> entry : upfSelector.allocateUpfsForSessions(upfVip, sessions, originalUpf, threshold).entrySet()) {
                executorService.execute(() -> {
                    migratePfcpSessions(upfVip, entry.getValue(), originalUpf, entry.getKey());
                });
            }
            executorService.shutdown();
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            executorService.shutdownNow();
            log.info(e.toString());
        }
    }

    private void migratePfcpSessions(Ip4Address upfVip, Collection<PfcpSession> sessions, UpfInstance originalUpf, UpfInstance targetUpf) {
        log.info("migratePfcpSessions: start to migrate {} to ({}, {})", Arrays.toString(sessions.toArray()), targetUpf.name(), targetUpf.ip());
        ExecutorService executorService = Executors.newCachedThreadPool();
        for (PfcpSession session : sessions) {
            executorService.execute(() -> {
                migratePfcpSession(upfVip, session, originalUpf, targetUpf);
            });
        }
        executorService.shutdown();
        try {
            executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            executorService.shutdownNow();
            log.info(e.toString());
        }
    }

    private void migratePfcpSession(Ip4Address upfVip, PfcpSession session, UpfInstance originalUpf, UpfInstance targetUpf) {
        FSeid fseid = session.fseid();
        log.info("migratePfcpSession: start to migrate {}", fseid);
        session.setUpfDip(targetUpf.ip());
        originalUpf.removePfcpSession(fseid);
        targetUpf.addPfcpSession(session);

        P4Manager p4Manager = upfServices.get(upfVip).getP4Manager();
        HashMap<FTeid, FlowRule> newDnatRules = new HashMap<FTeid, FlowRule>();
        BiConsumer<FTeid, FlowRule> updateDnatRule = (fteid, rule) -> {
            // Directly install new flow rule, without deleting the stale one.
            // Originally I perform deletion first, but it turns out that both flow rules are deleted.
            newDnatRules.put(fteid, p4Manager.installUpfDnatTableRule(fteid.getIpv4(), fteid.getTeid(), targetUpf.ip()));
        };

        if (originalUpf.nodeIp().equals(targetUpf.nodeIp())) {
            log.info("1. Update static routes on node");
            String cmd = String.format("ip route change %s/32 proto static dev %s", session.ueIp(), targetUpf.getHostIntfName());
            k8sManager.execHostRouteAgentCommand(originalUpf.nodeIp(), cmd);

            log.info("2. Update relative P4 DNAT rules");
            session.forEachEndpoint(updateDnatRule);
            session.setFteidToDnatRules(newDnatRules);
        } else {
            log.info("1. Install new static route on target ndoe");
            String cmd = String.format("ip route add %s/32 proto static dev %s", session.ueIp(), targetUpf.getHostIntfName());
            k8sManager.execHostRouteAgentCommand(targetUpf.nodeIp(), cmd);

            log.info("2. Update relative P4 DNAT and routing rules");
            session.forEachEndpoint(updateDnatRule);
            session.setFteidToDnatRules(newDnatRules);
            FlowRule n6DlRoutingRule = p4Manager.installIpRouteTableRule(session.ueIp(), targetUpf.nodePortNumber(), targetUpf.nodeMac());
            session.setN6DlRoutingRule(n6DlRoutingRule);
            p4Manager.waitFlowRulesActionComplete(n6DlRoutingRule);
        }

        p4Manager.waitFlowRulesActionComplete(new ArrayList<FlowRule>(newDnatRules.values()).toArray(new FlowRule[0]));
        log.info("migratePfcpSession: migration of {} completed", fseid);
    }

    protected synchronized void deletePfcpSessionHandler(FSeid fseid, Ip4Address vip) {
        PfcpSession session = fseidToPfcpSession.get(fseid);
        UpfInstance upf = upfIpToUpfInstance.get(vip).get(session.upfDip());
        fseidToPfcpSession.remove(fseid);
        upf.removePfcpSession(fseid);

        session.forEachEndpoint((fteid, rule) -> flowRuleService.removeFlowRules(rule));
        flowRuleService.removeFlowRules(session.n6DlRoutingRule());

        String cmd = String.format("ip route del %s/32 dev %s", session.ueIp(), upf.getHostIntfName());
        k8sManager.execHostRouteAgentCommand(upf.nodeIp(), cmd);
    }

    // Proxy ARP
    private class ArpProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            ConnectPoint inCp = context.inPacket().receivedFrom();
            Ethernet ethPkt = context.inPacket().parsed();
            if (ethPkt == null || ethPkt.getEtherType() != Ethernet.TYPE_ARP) {
                return;
            }

            ARP arpPkt = (ARP) ethPkt.getPayload();
            Ip4Address dstIp = Ip4Address.valueOf(arpPkt.getTargetProtocolAddress());

            if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
                MacAddress mac = p4Managers.get(inCp.deviceId()).getVirtualMac();
                Ethernet reply = ARP.buildArpReply(dstIp, mac, ethPkt);
                DefaultOutboundPacket pkt = new DefaultOutboundPacket(inCp.deviceId(),
                        DefaultTrafficTreatment.builder().setOutput(inCp.port()).build(),
                        ByteBuffer.wrap(reply.serialize()));
                packetService.emit(pkt);
            }
        }
    }

    private class AlertManagerWebhookHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            log.info("AlertManagerWebhookHandler, Thread #{}", Thread.currentThread().getId());
            String bytes = new String(exchange.getRequestBody().readAllBytes());
            JsonNode reqBody = new ObjectMapper().readTree(bytes);

            // Reply to AlertManager before actually performing rebalancing.
            // This should prevent the retries.
            exchange.getResponseHeaders().put("Connection", Collections.singletonList("close"));
            String response = "Alerts received";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.flush();
            exchange.close();

            for (JsonNode alert : reqBody.get("alerts")) {
                String name = alert.get("labels").get("pod").asText();
                Ip4Address dip = Ip4Address.valueOf(alert.get("labels").get("pod_ip").asText());
                Ip4Address vip = null;
                for (Map.Entry<Ip4Address, HashMap<Ip4Address, UpfInstance>> entry : upfIpToUpfInstance.entrySet()) {
                    if (entry.getValue().containsKey(dip)) {
                        vip = entry.getKey();
                        break;
                    }
                }
                double threshold = Double.valueOf(alert.get("annotations").get("threshold").asText()).doubleValue();
                overloadHandler(name, vip, dip, threshold);
            }
        }
    }
}
