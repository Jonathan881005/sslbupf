package nctu.winlab.ha5gup;

import java.util.Objects;
import org.onlab.packet.Ip4Address;

public class FTeid {
    public FTeid(int teid, Ip4Address ipv4) {
        this.teid = teid;
        this.ipv4 = ipv4;
    }

    public synchronized int getTeid() {
        return teid;
    }

    public synchronized Ip4Address getIpv4() {
        return ipv4;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof FTeid)) {
            return false;
        }
        FTeid that = (FTeid) obj;
        return teid == that.teid && ipv4.equals(that.ipv4);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teid, ipv4);
    }

    private int teid;
    private Ip4Address ipv4;
}
