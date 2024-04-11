package nctu.winlab.ha5gup;

import org.onlab.packet.Ip4Address;

public class UpfService {
    public UpfService(Ip4Address ipv4, boolean isPsa, P4Manager p4Manager) {
        this.ipv4 = ipv4;
        this.isPsa = isPsa;
        this.p4Manager = p4Manager;
    }

    public Ip4Address getIpv4() {
        return ipv4;
    }

    public boolean getIsPsa() {
        return isPsa;
    }

    public P4Manager getP4Manager() {
        return p4Manager;
    }

    private Ip4Address ipv4;
    private boolean isPsa;
    private P4Manager p4Manager;
}
