package nctu.winlab.ha5gup;

import java.util.Objects;
import org.onlab.packet.Ip4Address;

public class FSeid {
    public FSeid(long seid, Ip4Address ipv4) {
        this.seid = seid;
        this.ipv4 = ipv4;
    }

    public synchronized long getSeid() {
        return seid;
    }

    public synchronized Ip4Address getIpv4() {
        return ipv4;
    }

    @Override
    public String toString() {
        return String.format("(%s, %d)", ipv4, seid);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FSeid)) {
            return false;
        }
        FSeid that = (FSeid) obj;
        return seid == that.seid && ipv4.equals(that.ipv4);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seid, ipv4);
    }

    private long seid;
    private Ip4Address ipv4;
}
