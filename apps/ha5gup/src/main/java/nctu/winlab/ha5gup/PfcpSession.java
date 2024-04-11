package nctu.winlab.ha5gup;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.onlab.packet.Ip4Address;
import org.onosproject.net.flow.FlowRule;

public class PfcpSession implements Comparable<PfcpSession> {
    private FSeid fseid;
    private Ip4Address upfDip;
    private Ip4Address ueIp;
    // TODO: I think fteidToDnatRules should only have 2 entries for ULCL, and 1 entry for PSA.
    //       Maybe I should get rid of the HashMap.
    private HashMap<FTeid, FlowRule> fteidToDnatRules;
    private FlowRule n6DlRoutingRule; // N6 downlink routing rule.
    private Double throughput; // up/downlink throughput of the PfcpSession (Gbps)

    public PfcpSession(FSeid fseid, Ip4Address upfDip, Ip4Address ueIp) {
        this.fseid = fseid;
        this.upfDip = upfDip;
        this.ueIp = ueIp;
        this.fteidToDnatRules = new HashMap<FTeid, FlowRule>();
        this.throughput = Double.valueOf(0);
    }

    public synchronized FSeid fseid() {
        return fseid;
    }

    public synchronized Ip4Address upfDip() {
        return upfDip;
    }

    public synchronized void setUpfDip(Ip4Address upfDip) {
        this.upfDip = upfDip;
    }

    public synchronized Ip4Address ueIp() {
        return ueIp;
    }

    public synchronized void forEachEndpoint(BiConsumer<FTeid, FlowRule> action) {
        fteidToDnatRules.forEach(action);
    }

    public synchronized Map<FTeid, FlowRule> fteidToDnatRules() {
        return Collections.unmodifiableMap(new HashMap<FTeid, FlowRule>(fteidToDnatRules));
    }

    public synchronized void setFteidToDnatRules(HashMap<FTeid, FlowRule> fteidToDnatRules) {
        this.fteidToDnatRules = fteidToDnatRules;
    }

    public synchronized boolean hasEndpoint(FTeid fteid) {
        return fteidToDnatRules.containsKey(fteid);
    }

    public synchronized void addEndpoint(FTeid fteid, FlowRule rule) {
        fteidToDnatRules.put(fteid, rule);
    }

    public synchronized FlowRule n6DlRoutingRule() {
        return n6DlRoutingRule;
    }

    public synchronized void setN6DlRoutingRule(FlowRule rule) {
        n6DlRoutingRule = rule;
    }

    public double throughput() {
        return throughput.doubleValue();
    }

    public void updateThroughput(double throughput) {
        this.throughput = Double.valueOf(throughput);
    }

    @Override
    public String toString() {
        return fseid.toString();
    }

    @Override
    public int compareTo(PfcpSession other) {
        return Double.compare(throughput, other.throughput);
    }
}
