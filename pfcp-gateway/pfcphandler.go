package main

import (
	"log"
	"net"
	"sync"

	"github.com/wmnsk/go-pfcp/ie"
	"github.com/wmnsk/go-pfcp/message"
)

const (
	NOT_IMPLEMENTED_YET = "Not implemented yet"
)

// The returned boolean value of InputHandler indicates whether this packet should be further processed by the caller.
// The returned slice of OutputHandler is the packet for caller to send.
type PfcpHandler interface {
	SouthboundInputHandler() bool
	SouthboundOutputHandler(upfDip string) []byte

	NorthboundInputHandler() bool
	NorthboundOutputHandler() []byte
}

func NewPfcpHandler(pg *PfcpGateway, upfVip, upfDip string, packet []byte) PfcpHandler {
	pfcp, err := message.ParseHeader(packet)
	if err != nil {
		log.Fatal(err)
	}

	switch pfcp.MessageType() {
	case message.MsgTypeAssociationSetupRequest:
		return NewAssociationSetupRequestHandler(pg, upfVip, packet)
	case message.MsgTypeAssociationSetupResponse:
		return NewAssociationSetupResponseHandler(pg, upfVip, packet)
	case message.MsgTypeSessionEstablishmentRequest:
		return NewSessionEstablishmentRequestHandler(pg, upfVip, packet)
	case message.MsgTypeSessionEstablishmentResponse:
		return NewSessionEstablishmentResponseHandler(pg, upfVip, packet)
	case message.MsgTypeSessionModificationRequest:
		return NewSessionModificationRequestHandler(pg, upfVip, packet)
	case message.MsgTypeSessionModificationResponse:
		return NewSessionModificationResponseHandler(pg, upfVip, packet)
	case message.MsgTypeSessionDeletionRequest:
		return NewSessionDeletionRequestHandler(pg, upfVip, packet)
	case message.MsgTypeSessionDeletionResponse:
		return NewSessionDeletionResponseHandler(pg, upfVip, packet)
	case message.MsgTypeHeartbeatRequest:
		return NewHeartbeatRequestHandler(pg, upfVip, upfDip, packet)
	case message.MsgTypeHeartbeatResponse:
		return NewHeartbeatResponseHandler(pg, upfVip, upfDip, packet)
	default:
		log.Fatal("Unknown PFCP message type: ", pfcp.MessageType())
	}
	return nil
}

type AssociationSetupRequestHandler struct {
	pg            *PfcpGateway
	upfVip        string
	packet        []byte
	assocSetupReq *message.AssociationSetupRequest
}

func NewAssociationSetupRequestHandler(pg *PfcpGateway, upfVip string, packet []byte) *AssociationSetupRequestHandler {
	assocSetupReq, err := message.ParseAssociationSetupRequest(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &AssociationSetupRequestHandler{pg: pg, upfVip: upfVip, packet: packet, assocSetupReq: assocSetupReq}
}

func (h *AssociationSetupRequestHandler) SouthboundInputHandler() bool {
	h.pg.createAssocSetupReq(h.upfVip, h.assocSetupReq)
	return true
}

func (h *AssociationSetupRequestHandler) SouthboundOutputHandler(upfDip string) []byte {
	return h.packet
}

func (h *AssociationSetupRequestHandler) NorthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *AssociationSetupRequestHandler) NorthboundOutputHandler() []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

type AssociationSetupResponseHandler struct {
	pg             *PfcpGateway
	upfVip         string
	assocSetupResp *message.AssociationSetupResponse
}

func NewAssociationSetupResponseHandler(pg *PfcpGateway, upfVip string, packet []byte) *AssociationSetupResponseHandler {
	assocSetupResp, err := message.ParseAssociationSetupResponse(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &AssociationSetupResponseHandler{pg: pg, upfVip: upfVip, assocSetupResp: assocSetupResp}
}

func (h *AssociationSetupResponseHandler) SouthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *AssociationSetupResponseHandler) SouthboundOutputHandler(upfDip string) []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *AssociationSetupResponseHandler) NorthboundInputHandler() bool {
	return true
}

func (h *AssociationSetupResponseHandler) NorthboundOutputHandler() []byte {
	h.assocSetupResp.NodeID = ie.NewNodeID(h.upfVip, "", "")
	// free5gc v3.0.4 uses this IE
	h.modifyUserPlaneIpResourceInformation()
	packet, err := h.assocSetupResp.Marshal()
	if err != nil {
		log.Fatal(err)
	}
	return packet
}

func (h *AssociationSetupResponseHandler) modifyUserPlaneIpResourceInformation() {
	if h.assocSetupResp.UserPlaneIPResourceInformation != nil {
		for i, tmpIe := range h.assocSetupResp.UserPlaneIPResourceInformation {
			info, err := tmpIe.UserPlaneIPResourceInformation()
			if err != nil {
				log.Fatal(err)
			}
			h.assocSetupResp.UserPlaneIPResourceInformation[i] = ie.NewUserPlaneIPResourceInformation(info.Flags, info.TEIDRange, h.upfVip, info.IPv6Address.String(), info.NetworkInstance, info.SourceInterface)
		}
	}
}

type SessionEstablishmentRequestHandler struct {
	pg         *PfcpGateway
	upfVip     string
	sessEstReq *message.SessionEstablishmentRequest
	mu         sync.Mutex
}

func NewSessionEstablishmentRequestHandler(pg *PfcpGateway, upfVip string, packet []byte) *SessionEstablishmentRequestHandler {
	sessEstReq, err := message.ParseSessionEstablishmentRequest(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &SessionEstablishmentRequestHandler{pg: pg, upfVip: upfVip, sessEstReq: sessEstReq}
}

func (h *SessionEstablishmentRequestHandler) SouthboundInputHandler() bool {
	// TS 29.244 v15.3.0: 5.5.2 F-TEID allocation in the CP function.
	// free5gc uses this scheme by default.
	fteids, ueip := h.getFTeidAndUeIpInSessEstReq()
	// We only use F-SEID from UPF perspective.
	h.pg.updateSessionMetadata(h.sessEstReq.Sequence(), h.sessEstReq.Header, nil, fteids, ueip)
	return true
}

func (h *SessionEstablishmentRequestHandler) SouthboundOutputHandler(upfDip string) []byte {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.modifyFTeidInSessEstReq(upfDip, h.sessEstReq)
	packet, err := h.sessEstReq.Marshal()
	if err != nil {
		log.Fatal(err)
	}
	return packet
}

func (h *SessionEstablishmentRequestHandler) NorthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *SessionEstablishmentRequestHandler) NorthboundOutputHandler() []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *SessionEstablishmentRequestHandler) getFTeidAndUeIpInSessEstReq() (fteids []*ie.FTEIDFields, ueIp *ie.UEIPAddressFields) {
	for _, createPdr := range h.sessEstReq.CreatePDR {
		for _, createPdrCie := range createPdr.ChildIEs {
			if createPdrCie.Type == ie.PDI {
				for _, pdiCie := range createPdrCie.ChildIEs {
					switch pdiCie.Type {
					case ie.FTEID:
						fteid, err := pdiCie.FTEID()
						if err != nil {
							log.Fatal(err)
						}
						if fteid.Flags&4 == 0 {
							// F-TEID is meaningful only if CH(CHOOSE) bit is not set.
							fteids = append(fteids, fteid)
						}
					case ie.UEIPAddress:
						// Assuming all UE IP appeared in a message are the same one.
						if ueIp == nil {
							ueIpAddress, err := pdiCie.UEIPAddress()
							if err != nil {
								log.Fatal(err)
							}
							if ueIpAddress.Flags&2 == 0 {
								log.Fatal("UE IP is not IPv4.")
							}
							ueIp = ueIpAddress
						}
					}
				}
			}
		}
	}
	return fteids, ueIp
}

func (h *SessionEstablishmentRequestHandler) modifyFTeidInSessEstReq(dip string, sessEstReq *message.SessionEstablishmentRequest) {
	for i, createPdr := range sessEstReq.CreatePDR {
		for j, createPdrCie := range createPdr.ChildIEs {
			if createPdrCie.Type == ie.PDI {
				for k, pdiCie := range createPdrCie.ChildIEs {
					if pdiCie.Type == ie.FTEID {
						fteid, err := pdiCie.FTEID()
						if err != nil {
							log.Fatal(err)
						}
						if fteid.Flags&4 == 0 {
							// F-TEID is meaningful only if CH(CHOOSE) bit is not set.
							sessEstReq.CreatePDR[i].ChildIEs[j].ChildIEs[k] = ie.NewFTEID(fteid.Flags, fteid.TEID, net.ParseIP(dip), fteid.IPv6Address, fteid.ChooseID)
						}
					}
				}
			}
		}
	}
}

type SessionEstablishmentResponseHandler struct {
	pg          *PfcpGateway
	upfVip      string
	sessEstResp *message.SessionEstablishmentResponse
}

func NewSessionEstablishmentResponseHandler(pg *PfcpGateway, upfVip string, packet []byte) *SessionEstablishmentResponseHandler {
	sessEstResp, err := message.ParseSessionEstablishmentResponse(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &SessionEstablishmentResponseHandler{pg: pg, upfVip: upfVip, sessEstResp: sessEstResp}
}

func (h *SessionEstablishmentResponseHandler) SouthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *SessionEstablishmentResponseHandler) SouthboundOutputHandler(upfDip string) []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *SessionEstablishmentResponseHandler) NorthboundInputHandler() bool {
	return true
}

func (h *SessionEstablishmentResponseHandler) NorthboundOutputHandler() []byte {
	// TODO: Merge the MODIFY and GET operation.
	h.sessEstResp.NodeID = ie.NewNodeID(h.upfVip, "", "")
	h.modifyFSeid()
	h.modifyFTeidInSessEstResp()

	fseid, err := h.sessEstResp.UPFSEID.FSEID()
	if err != nil {
		log.Fatal(err)
	}
	h.pg.appendSessEstModReq(h.upfVip, fseid.SEID, h.pg.getSessEstReqFromSessionMetadata(h.sessEstResp.Sequence()))

	// TS 29.244 v15.3.0: 5.5.3 F-TEID allocation in the UP function.
	// open5gs uses this scheme by default.
	fteids := h.getFTeidInSessEstResp()
	h.pg.updateSessionMetadata(h.sessEstResp.Sequence(), nil, fseid, fteids, nil)
	h.pg.notifyPfcpSessionAddEvent(h.sessEstResp.Sequence())

	packet, err := h.sessEstResp.Marshal()
	if err != nil {
		log.Fatal(err)
	}
	return packet
}

func (h *SessionEstablishmentResponseHandler) getFTeidInSessEstResp() (fteids []*ie.FTEIDFields) {
	for _, createdPdr := range h.sessEstResp.CreatedPDR {
		for _, cie := range createdPdr.ChildIEs {
			if cie.Type == ie.FTEID {
				fteid, err := cie.FTEID()
				if err != nil {
					log.Fatal(err)
				}
				fteids = append(fteids, fteid)
			}
		}
	}
	return fteids
}

func (h *SessionEstablishmentResponseHandler) modifyFSeid() {
	fseid, err := h.sessEstResp.UPFSEID.FSEID()
	if err != nil {
		log.Fatal(err)
	}
	h.sessEstResp.UPFSEID = ie.NewFSEID(fseid.SEID, net.ParseIP(h.upfVip), fseid.IPv6Address)
}

func (h *SessionEstablishmentResponseHandler) modifyFTeidInSessEstResp() {
	for i, createdPdr := range h.sessEstResp.CreatedPDR {
		for j, cie := range createdPdr.ChildIEs {
			if cie.Type == ie.FTEID {
				fteid, err := cie.FTEID()
				if err != nil {
					log.Fatal(err)
				}
				h.sessEstResp.CreatedPDR[i].ChildIEs[j] = ie.NewFTEID(fteid.Flags, fteid.TEID, net.ParseIP(h.upfVip), fteid.IPv6Address, fteid.ChooseID)
			}
		}
	}
}

type SessionModificationRequestHandler struct {
	pg         *PfcpGateway
	upfVip     string
	sessModReq *message.SessionModificationRequest
	mu         sync.Mutex
}

func NewSessionModificationRequestHandler(pg *PfcpGateway, upfVip string, packet []byte) *SessionModificationRequestHandler {
	sessModReq, err := message.ParseSessionModificationRequest(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &SessionModificationRequestHandler{pg: pg, upfVip: upfVip, sessModReq: sessModReq}
}

func (h *SessionModificationRequestHandler) SouthboundInputHandler() bool {
	h.pg.appendSessEstModReq(h.upfVip, h.sessModReq.SEID(), h.sessModReq.Header)

	fseid := ie.NewFSEIDFields(h.sessModReq.SEID(), net.ParseIP(h.upfVip), nil)
	// TS 29.244 v15.3.0: 5.5.2 F-TEID allocation in the CP function.
	// free5gc uses this scheme by default.
	fteids := h.getFTeidInSessModReq()
	h.pg.updateSessionMetadata(h.sessModReq.Sequence(), nil, fseid, fteids, nil)
	h.pg.notifyPfcpSessionAddEvent(h.sessModReq.Sequence())
	return true
}

func (h *SessionModificationRequestHandler) SouthboundOutputHandler(upfDip string) []byte {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.modifyFTeidInSessModReq(upfDip, h.sessModReq)
	packet, err := h.sessModReq.Marshal()
	if err != nil {
		log.Fatal(err)
	}
	return packet
}

func (h *SessionModificationRequestHandler) NorthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *SessionModificationRequestHandler) NorthboundOutputHandler() []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *SessionModificationRequestHandler) getFTeidInSessModReq() (fteids []*ie.FTEIDFields) {
	for _, createPdr := range h.sessModReq.CreatePDR {
		for _, createPdrCie := range createPdr.ChildIEs {
			if createPdrCie.Type == ie.PDI {
				for _, pdiCie := range createPdrCie.ChildIEs {
					if pdiCie.Type == ie.FTEID {
						fteid, err := pdiCie.FTEID()
						if err != nil {
							log.Fatal(err)
						}
						if fteid.Flags&4 == 0 {
							// F-TEID is meaningful only if CH(CHOOSE) bit is not set.
							fteids = append(fteids, fteid)
						}
					}
				}
			}
		}
	}
	for _, updatePdr := range h.sessModReq.UpdatePDR {
		for _, updatePdrCie := range updatePdr.ChildIEs {
			if updatePdrCie.Type == ie.PDI {
				for _, pdiCie := range updatePdrCie.ChildIEs {
					if pdiCie.Type == ie.FTEID {
						fteid, err := pdiCie.FTEID()
						if err != nil {
							log.Fatal(err)
						}
						if fteid.Flags&4 == 0 {
							// F-TEID is meaningful only if CH(CHOOSE) bit is not set.
							fteids = append(fteids, fteid)
						}
					}
				}
			}
		}
	}
	return fteids
}

func (h *SessionModificationRequestHandler) modifyFTeidInSessModReq(dip string, sessModReq *message.SessionModificationRequest) {
	for i, createPdr := range sessModReq.CreatePDR {
		for j, createPdrCie := range createPdr.ChildIEs {
			if createPdrCie.Type == ie.PDI {
				for k, pdiCie := range createPdrCie.ChildIEs {
					if pdiCie.Type == ie.FTEID {
						fteid, err := pdiCie.FTEID()
						if err != nil {
							log.Fatal(err)
						}
						if fteid.Flags&4 == 0 {
							// F-TEID is meaningful only if CH(CHOOSE) bit is not set.
							sessModReq.CreatePDR[i].ChildIEs[j].ChildIEs[k] = ie.NewFTEID(fteid.Flags, fteid.TEID, net.ParseIP(dip), fteid.IPv6Address, fteid.ChooseID)
						}
					}
				}
			}
		}
	}
	for i, updatePdr := range h.sessModReq.UpdatePDR {
		for j, updatePdrCie := range updatePdr.ChildIEs {
			if updatePdrCie.Type == ie.PDI {
				for k, pdiCie := range updatePdrCie.ChildIEs {
					if pdiCie.Type == ie.FTEID {
						fteid, err := pdiCie.FTEID()
						if err != nil {
							log.Fatal(err)
						}
						if fteid.Flags&4 == 0 {
							// F-TEID is meaningful only if CH(CHOOSE) bit is not set.
							h.sessModReq.UpdatePDR[i].ChildIEs[j].ChildIEs[k] = ie.NewFTEID(fteid.Flags, fteid.TEID, net.ParseIP(dip), fteid.IPv6Address, fteid.ChooseID)
						}
					}
				}
			}
		}
	}
}

// TODO
type SessionModificationResponseHandler struct {
	pg          *PfcpGateway
	upfVip      string
	packet      []byte
	sessModResp *message.SessionModificationResponse
}

func NewSessionModificationResponseHandler(pg *PfcpGateway, upfVip string, packet []byte) *SessionModificationResponseHandler {
	sessModResp, err := message.ParseSessionModificationResponse(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &SessionModificationResponseHandler{pg: pg, upfVip: upfVip, packet: packet, sessModResp: sessModResp}
}

func (h *SessionModificationResponseHandler) SouthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *SessionModificationResponseHandler) SouthboundOutputHandler(upfDip string) []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *SessionModificationResponseHandler) NorthboundInputHandler() bool {
	return true
}

func (h *SessionModificationResponseHandler) NorthboundOutputHandler() []byte {
	return h.packet
}

type SessionDeletionRequestHandler struct {
	pg     *PfcpGateway
	upfVip string
	packet []byte
}

func NewSessionDeletionRequestHandler(pg *PfcpGateway, upfVip string, packet []byte) *SessionDeletionRequestHandler {
	return &SessionDeletionRequestHandler{pg: pg, upfVip: upfVip, packet: packet}
}

func (h *SessionDeletionRequestHandler) SouthboundInputHandler() bool {
	sessDelReq, err := message.ParseSessionDeletionRequest(h.packet)
	if err != nil {
		log.Fatal(err)
	}

	// The session is deleted. No need to keep related messages anymore.
	h.pg.deleteSessEstModReqs(h.upfVip, sessDelReq.SEID())

	fseid := ie.NewFSEIDFields(sessDelReq.SEID(), net.ParseIP(h.upfVip), nil)
	h.pg.notifyPfcpSessionDeleteEvent(fseid, h.upfVip)
	return true
}

func (h *SessionDeletionRequestHandler) SouthboundOutputHandler(upfDip string) []byte {
	return h.packet
}

func (h *SessionDeletionRequestHandler) NorthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *SessionDeletionRequestHandler) NorthboundOutputHandler() []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

type SessionDeletionResponseHandler struct {
	pg     *PfcpGateway
	upfVip string
	packet []byte
}

func NewSessionDeletionResponseHandler(pg *PfcpGateway, upfVip string, packet []byte) *SessionDeletionResponseHandler {
	return &SessionDeletionResponseHandler{pg: pg, upfVip: upfVip, packet: packet}
}

func (h *SessionDeletionResponseHandler) SouthboundInputHandler() bool {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return true
}

func (h *SessionDeletionResponseHandler) SouthboundOutputHandler(upfDip string) []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *SessionDeletionResponseHandler) NorthboundInputHandler() bool {
	return true
}

func (h *SessionDeletionResponseHandler) NorthboundOutputHandler() []byte {
	return h.packet
}

// HeartbeatRequestHandler perform "Proxy PFCP Heartbeat".
// When receiving Heartbeat request sent from SMF, the handler forges and
// sends the response, then the request is dropped.
// This works identically in reverse order. When receiving Heartbeat
// request sent from UPF instances, just replies a response and ignore the
// request packet.
type HeartbeatRequestHandler struct {
	pg     *PfcpGateway
	upfVip string
	upfDip string
	packet []byte
	hbReq  *message.HeartbeatRequest
}

func NewHeartbeatRequestHandler(pg *PfcpGateway, upfVip, upfDip string, packet []byte) *HeartbeatRequestHandler {
	hbReq, err := message.ParseHeartbeatRequest(packet)
	if err != nil {
		log.Fatal(err)
	}
	return &HeartbeatRequestHandler{pg: pg, upfVip: upfVip, upfDip: upfDip, packet: packet, hbReq: hbReq}
}

func (h *HeartbeatRequestHandler) SouthboundInputHandler() bool {
	// "Proxy PFCP Heartbeat": Respond heartbeat requests sent by SMF.
	hbResp, err := message.NewHeartbeatResponse(h.hbReq.Sequence(), h.pg.recoveryTs).Marshal()
	if err != nil {
		log.Fatal(err)
	}
	h.pg.sendToSmf(h.upfVip, hbResp)
	log.Printf("======== [%3d] SMF <-- UPF (%15s): type %v ========", h.hbReq.Sequence(), h.upfVip, message.MsgTypeHeartbeatResponse)
	return false
}

func (h *HeartbeatRequestHandler) SouthboundOutputHandler(upfDip string) []byte {
	return h.packet
}

func (h *HeartbeatRequestHandler) NorthboundInputHandler() bool {
	// "Proxy PFCP Heartbeat": Respond heartbeat requests sent by UPF instances.
	hbResp, err := message.NewHeartbeatResponse(h.hbReq.Sequence(), h.pg.recoveryTs).Marshal()
	if err != nil {
		log.Fatal(err)
	}
	h.pg.sendAsyncPktToUpf(h.upfVip, h.upfDip, hbResp)
	log.Printf("======== [%3d] SMF --> UPF (%15s): type %v ========", h.hbReq.Sequence(), h.upfDip, message.MsgTypeHeartbeatResponse)
	return false
}

func (h *HeartbeatRequestHandler) NorthboundOutputHandler() []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

// Becasue of the Proxy PFCP Heartbeat mechanism, PFCP Gateway should not receive heartbeat response.
type HeartbeatResponseHandler struct {
}

func NewHeartbeatResponseHandler(pg *PfcpGateway, upfVip, upfDip string, packet []byte) *HeartbeatResponseHandler {
	return &HeartbeatResponseHandler{}
}

func (h *HeartbeatResponseHandler) SouthboundInputHandler() bool {
	return false
}

func (h *HeartbeatResponseHandler) SouthboundOutputHandler(upfDip string) []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}

func (h *HeartbeatResponseHandler) NorthboundInputHandler() bool {
	return false
}

func (h *HeartbeatResponseHandler) NorthboundOutputHandler() []byte {
	log.Fatal(NOT_IMPLEMENTED_YET)
	return nil
}
