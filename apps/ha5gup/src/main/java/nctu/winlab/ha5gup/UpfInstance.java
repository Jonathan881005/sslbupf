package nctu.winlab.ha5gup;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.net.flow.FlowRule;

public class UpfInstance implements Comparable<UpfInstance> {
    private String name;
    private Ip4Address ip;
    private Long nodePortNumber;
    private MacAddress nodeMac;
    private Ip4Address nodeIp;
    private FlowRule snatRule;
    private FlowRule routingRule;
    private HashMap<FSeid, PfcpSession> pfcpSessions;
    private Double throughput; // Receiving throughput of the UPF instance (Gbps)

    public UpfInstance(String name, Ip4Address ip, Long nodePortNumber, MacAddress nodeMac, Ip4Address nodeIp, FlowRule snatRule, FlowRule routingRule) {
        this.name = name;
        this.ip = ip;
        this.nodePortNumber = nodePortNumber;
        this.nodeMac = nodeMac;
        this.nodeIp = nodeIp;
        this.snatRule = snatRule;
        this.routingRule = routingRule;
        this.pfcpSessions = new HashMap<FSeid, PfcpSession>();
        this.throughput = Double.valueOf(0);
    }

    public String name() {
        return name;
    }

    public synchronized Ip4Address ip() {
        return ip;
    }

    public synchronized Long nodePortNumber() {
        return nodePortNumber;
    }

    public synchronized MacAddress nodeMac() {
        return nodeMac;
    }

    public synchronized Ip4Address nodeIp() {
        return nodeIp;
    }

    public synchronized FlowRule snatRule() {
        return snatRule;
    }

    public synchronized FlowRule routingRule() {
        return routingRule;
    }

    public synchronized Map<FSeid, PfcpSession> pfcpSessions() {
        return Collections.unmodifiableMap(new HashMap<FSeid, PfcpSession>(pfcpSessions));
    }

    public synchronized void addPfcpSession(PfcpSession session) {
        pfcpSessions.put(session.fseid(), session);
    }

    public synchronized void removePfcpSession(FSeid fseid) {
        pfcpSessions.remove(fseid);
    }

    public double throughput() {
        return throughput.doubleValue();
    }

    public void updateThroughput(double throughput) {
        this.throughput = Double.valueOf(throughput);
    }

    public synchronized String getHostIntfName() {
        return null;
    }

    @Override
    public int compareTo(UpfInstance other) {
        return Double.compare(throughput, other.throughput);
    }
}
