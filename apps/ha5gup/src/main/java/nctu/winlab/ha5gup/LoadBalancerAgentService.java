package nctu.winlab.ha5gup;

import nctu.winlab.ha5gup.proto.LoadBalancerAgentGrpc.LoadBalancerAgentImplBase;

import com.google.protobuf.Empty;
import org.onlab.packet.Ip4Address;
import org.slf4j.Logger;

import java.util.ArrayList;

import static org.slf4j.LoggerFactory.getLogger;

public class LoadBalancerAgentService extends LoadBalancerAgentImplBase {
    protected static final Logger log = getLogger(LoadBalancerAgentService.class);

    private UpflbControl upflbControl;

    public LoadBalancerAgentService(UpflbControl upflbControl) {
        this.upflbControl = upflbControl;
    }

    public void initialize(nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.InitializeRequest request,
            io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        log.info("initialize");
        upflbControl.initializeHandler(request.getCoreNetwork().getNumber(), request.getUpfServicesList());
        log.info("initialize: done");

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    public void addUpf(nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.AddUpfRequest request,
            io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        log.info("addUpf: {} (vip = {}, nodeIp = {})", request.getUpfDip(), request.getUpfVip(), request.getNodeIp());
        upflbControl.addUpfHandler(request.getName(), Ip4Address.valueOf(request.getUpfDip()), Ip4Address.valueOf(request.getUpfVip()), Ip4Address.valueOf(request.getNodeIp()));
        log.info("addUpf: done");

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    public void deleteUpf(nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.DeleteUpfRequest request,
            io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        log.info("deleteUpf: {} (vip = {})", request.getUpfDip(), request.getUpfVip());
        upflbControl.deleteUpfHandler(Ip4Address.valueOf(request.getUpfVip()), Ip4Address.valueOf(request.getUpfDip()));
        log.info("deleteUpf: done");

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    public void updatePfcpSession(nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.UpdatePfcpSessionRequest request,
            io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        FSeid fseid = new FSeid(request.getFseid().getSeid(), Ip4Address.valueOf(request.getFseid().getIpv4()));
        ArrayList<FTeid> fteids = new ArrayList<FTeid>();
        String entryMsg = String.format("updatePfcpSession: FSEID(%s, %d), TEIDs(", fseid.getIpv4(), fseid.getSeid());
        for (nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.FTeid fteid : request.getFteidsList()) {
            fteids.add(new FTeid(fteid.getTeid(), Ip4Address.valueOf(fteid.getIpv4())));
            entryMsg += String.format("%d, ", fteid.getTeid());
        }
        entryMsg = entryMsg.substring(0, entryMsg.length() - 2) + ")";

        log.info(entryMsg);
        upflbControl.updatePfcpSessionHandler(fseid, fteids, request.getUeIp().isEmpty() ? null : Ip4Address.valueOf(request.getUeIp()));
        log.info("updatePfcpSession: done");

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    public void deletePfcpSession(nctu.winlab.ha5gup.proto.LoadBalancerAgentOuterClass.DeletePfcpSessionRequest request,
            io.grpc.stub.StreamObserver<com.google.protobuf.Empty> responseObserver) {
        FSeid fseid = new FSeid(request.getFseid().getSeid(), Ip4Address.valueOf(request.getFseid().getIpv4()));
        log.info("deletePfcpSession: FSEID({}, {})", fseid.getIpv4(), fseid.getSeid());
        upflbControl.deletePfcpSessionHandler(fseid, fseid.getIpv4());
        log.info("deletePfcpSession: done");

        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
