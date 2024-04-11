package main

import (
	"log"
	"sync"

	"github.com/wmnsk/go-pfcp/message"
)

type DataStore interface {
	readAssocSetupReq(upfVip string) *message.AssociationSetupRequest
	createAssocSetupReq(upfVip string, assocSetupRequest *message.AssociationSetupRequest)
	// updateAssociation(upfVip string, assocSetupRequest *message.Header)

	readAllSessEstModReqs(upfVip string) map[uint64][]*message.Header
	readSessEstModReqs(upfVip string, seid uint64) []*message.Header
	appendSessEstModReq(upfVip string, seid uint64, sessRelatedRequest *message.Header)
	deleteSessEstModReqs(upfVip string, seid uint64)
	// updateSession(upfVip string, seid uint64, sessSetupRequest *message.Header)
}

type memDataStore struct {
	sync.RWMutex
	upfCtxs map[string]*upfContext
}

type upfContext struct {
	assocSetupRequest  *message.AssociationSetupRequest
	sessEstModRequests map[uint64][]*message.Header
}

func NewMemDataStore() *memDataStore {
	return &memDataStore{
		upfCtxs: make(map[string]*upfContext),
	}
}

func (ds *memDataStore) readAssocSetupReq(upfVip string) *message.AssociationSetupRequest {
	ds.RLock()
	defer ds.RUnlock()
	if _, exist := ds.upfCtxs[upfVip]; !exist {
		return nil
	}
	return ds.upfCtxs[upfVip].assocSetupRequest
}

func (ds *memDataStore) createAssocSetupReq(upfVip string, assocSetupRequest *message.AssociationSetupRequest) {
	ds.Lock()
	defer ds.Unlock()
	ds.upfCtxs[upfVip] = &upfContext{
		assocSetupRequest:  assocSetupRequest,
		sessEstModRequests: make(map[uint64][]*message.Header),
	}
}

func (ds *memDataStore) readAllSessEstModReqs(upfVip string) map[uint64][]*message.Header {
	ds.RLock()
	defer ds.RUnlock()
	if _, exist := ds.upfCtxs[upfVip]; !exist {
		return nil
	}
	allReqs := make(map[uint64][]*message.Header)
	for seid, reqs := range ds.upfCtxs[upfVip].sessEstModRequests {
		allReqs[seid] = make([]*message.Header, len(reqs))
		copy(allReqs[seid], reqs)
	}
	return allReqs
}

func (ds *memDataStore) readSessEstModReqs(upfVip string, seid uint64) []*message.Header {
	ds.RLock()
	defer ds.RUnlock()
	reqs := make([]*message.Header, len(ds.upfCtxs[upfVip].sessEstModRequests[seid]))
	copy(reqs, ds.upfCtxs[upfVip].sessEstModRequests[seid])
	return reqs
}

func (ds *memDataStore) appendSessEstModReq(upfVip string, seid uint64, sessRelatedRequest *message.Header) {
	ds.Lock()
	defer ds.Unlock()
	if _, exist := ds.upfCtxs[upfVip]; !exist {
		log.Fatalf("Association to %s has not established", upfVip)
	}
	ds.upfCtxs[upfVip].sessEstModRequests[seid] = append(ds.upfCtxs[upfVip].sessEstModRequests[seid], sessRelatedRequest)
}

func (ds *memDataStore) deleteSessEstModReqs(upfVip string, seid uint64) {
	ds.Lock()
	defer ds.Unlock()
	if _, exist := ds.upfCtxs[upfVip].sessEstModRequests[seid]; !exist {
		log.Fatalf("Session (%s, %v) is not existed", upfVip, seid)
	}
	delete(ds.upfCtxs[upfVip].sessEstModRequests, seid)
}
