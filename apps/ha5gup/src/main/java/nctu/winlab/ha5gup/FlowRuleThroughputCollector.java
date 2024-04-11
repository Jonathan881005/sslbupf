package nctu.winlab.ha5gup;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowId;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.pi.model.PiTableId;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;
import static org.onosproject.net.flow.FlowRuleEvent.Type.RULE_ADD_REQUESTED;
import static org.onosproject.net.flow.FlowRuleEvent.Type.RULE_REMOVE_REQUESTED;

public class FlowRuleThroughputCollector {
    protected static final Logger log = getLogger(FlowRuleThroughputCollector.class);

    private ApplicationId appId;
    private FlowRuleService flowRuleService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentHashMap<FlowId, FlowStat> flowStats = new ConcurrentHashMap<FlowId, FlowStat>();
    private final UpflbFlowRuleListener upflbFlowRuleListener = new UpflbFlowRuleListener();
    private final PiTableId upfDnatTableId = PiTableId.of("Ingress.upf_dnat_table");
    private final PiTableId ipRouteTableId = PiTableId.of("Ingress.ip_route_table");

    public FlowRuleThroughputCollector(ApplicationId appId, FlowRuleService flowRuleService) {
        this.appId = appId;
        this.flowRuleService = flowRuleService;

        flowRuleService.addListener(upflbFlowRuleListener);
        scheduler.scheduleAtFixedRate(() -> { collect(); }, 0, 3, TimeUnit.SECONDS);
    }

    public double getThroughput(FlowId flowId) {
        return flowStats.get(flowId).throughput;
    }

    public void shutdownCollector() {
        scheduler.shutdownNow();
    }

    public void removeFlowRuleListener() {
        flowRuleService.removeListener(upflbFlowRuleListener);
    }

    private void collect() {
        try {
            for (FlowEntry entry : flowRuleService.getFlowEntriesById(appId)) {
                if (!entry.table().equals(upfDnatTableId) && !entry.table().equals(ipRouteTableId)) {
                    continue;
                }
                FlowId flowId = entry.id();
                long life = entry.life();
                long bytes = entry.bytes();
                if (life != flowStats.get(flowId).life) {
                    double throughput = (double)(bytes - flowStats.get(flowId).bytes) / (life - flowStats.get(flowId).life);
                    flowStats.get(flowId).life = life;
                    flowStats.get(flowId).bytes = bytes;
                    flowStats.get(flowId).throughput = throughput;
                    // log.info("{}: life = {}, bytes = {}, throughput = {} Mbps", flowId.toString(), life, bytes, throughput);
                }
            }
        } catch (Exception e) {
            // Mainly for catching ArithmeticException.
            log.info(e.toString());
        }
    }

    private class FlowStat {
        public FlowStat(long life, long bytes, long throughput) {
            this.life = life;
            this.bytes = bytes;
            this.throughput = throughput;
        }
        public long life;
        public long bytes;
        public double throughput;
    }

    private class UpflbFlowRuleListener implements FlowRuleListener {
        @Override
        public void event(FlowRuleEvent event) {
            FlowRule rule = event.subject();
            if (rule.appId() != appId.id() || (!rule.table().equals(upfDnatTableId) && !rule.table().equals(ipRouteTableId))) {
                return;
            }
            switch (event.type()) {
                case RULE_ADD_REQUESTED:
                    // log.info("RULE_ADD_REQUESTED in {}, {}", rule.table().toString(), rule.selector().criteria());
                    flowStats.put(rule.id(), new FlowStat(0, 0, 0));
                    break;
                case RULE_REMOVE_REQUESTED:
                    // log.info("RULE_REMOVE_REQUESTED in {}, {}", rule.table().toString(), rule.selector().criteria());
                    flowStats.remove(rule.id());
                    break;
                default:
                    break;
            }
        }
    }
}
