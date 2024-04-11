package nctu.winlab.ha5gup;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.net.flow.FlowRule;

public class VnfUpfInstance extends UpfInstance {
    private String hostIntfName;

    public VnfUpfInstance(String name, Ip4Address ip, Long nodePortNumber, MacAddress nodeMac, Ip4Address nodeIp, FlowRule snatRule, FlowRule routingRule, String hostIntfName) {
        super(name, ip, nodePortNumber, nodeMac, nodeIp, snatRule, routingRule);
        this.hostIntfName = hostIntfName;
    }

    @Override
    public synchronized String getHostIntfName() {
        return hostIntfName;
    }
}
