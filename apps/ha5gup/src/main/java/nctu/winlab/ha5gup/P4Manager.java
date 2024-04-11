package nctu.winlab.ha5gup;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.Device;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowId;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.PiCriterion;
import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionParamId;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiActionParam;
import org.slf4j.Logger;

import static org.onosproject.net.flow.FlowRuleEvent.Type.RULE_ADDED;
import static org.slf4j.LoggerFactory.getLogger;

public class P4Manager {
    protected static final Logger log = getLogger(P4Manager.class);

    private ApplicationId appId;
    private Device dev;
    private FlowRuleService flowRuleService;
    private MacAddress virtualMac; // Virtual MAC address of the managed P4 switch.

    private final HashMap<Ip4Address, UpfService> upfServices = new HashMap<Ip4Address, UpfService>();
    private final ConcurrentHashMap<FlowId, Semaphore> flowIdToSemaphore = new ConcurrentHashMap<FlowId, Semaphore>();
    private final UpflbFlowRuleListener upflbFlowRuleListener = new UpflbFlowRuleListener();

    public P4Manager(ApplicationId appId, Device dev, FlowRuleService flowRuleService, MacAddress virtualMac) {
        this.appId = appId;
        this.dev = dev;
        this.flowRuleService = flowRuleService;
        this.virtualMac = virtualMac;

        flowRuleService.addListener(upflbFlowRuleListener);

        // TODO: These rules should only be installed on HEAD and TAIL switches, respectively.
        // Hardcode gNB routing rule
        installIpRouteTableRule(Ip4Address.valueOf("10.0.0.103"), 18, MacAddress.valueOf("00:1b:21:bc:0e:a6"));
        // Hardcode DataNetwork routing rule
        installIpRouteTableRule(Ip4Address.valueOf("10.0.1.103"), 19, MacAddress.valueOf("00:1b:21:bc:0e:a7"));
    }

    public Device getDevice() {
        return dev;
    }

    public void addUpfService(Ip4Address upfVip, UpfService upfService) {
        upfServices.put(upfVip, upfService);
    }

    public HashMap<Ip4Address, UpfService> getUpfServices() {
        return upfServices;
    }

    public synchronized FlowRule installUpfDnatTableRule(Ip4Address vip, int teid, Ip4Address dip) {
        try {
            final PiCriterion.Builder criterionBuilder = PiCriterion.builder()
                    .matchTernary(PiMatchFieldId.of("hdr.ipv4.dst_addr"), vip.toInt(), 0xffffffff)
                    .matchTernary(PiMatchFieldId.of("hdr.gtpu.teid"), teid, 0xffffffff);
            final PiAction piAction = PiAction.builder().withId(PiActionId.of("Ingress.upf_dnat"))
                    .withParameter(new PiActionParam(PiActionParamId.of("upf_dip"), dip.toOctets())).build();
            final FlowRule flowRule = DefaultFlowRule.builder().fromApp(appId).forDevice(dev.id())
                    .forTable(PiTableId.of("Ingress.upf_dnat_table")).makePermanent().withPriority(65535)
                    .withSelector(DefaultTrafficSelector.builder().matchPi(criterionBuilder.build()).build())
                    .withTreatment(DefaultTrafficTreatment.builder().piTableAction(piAction).build()).build();
            Semaphore sem = new Semaphore(1, false);
            sem.acquire();
            flowIdToSemaphore.put(flowRule.id(), sem);
            flowRuleService.applyFlowRules(flowRule);
            log.info("installUpfDnatTableRule: fteid = ({}, {}), upfDip = {}", vip, teid, dip);
            return flowRule;
        } catch (Exception e) {
            log.error(e.toString());
            return null;
        }
    }

    public synchronized FlowRule installUpfSnatTableRule(Ip4Address dip, Ip4Address vip) {
        try {
            final PiCriterion.Builder criterionBuilder = PiCriterion.builder()
                    .matchExact(PiMatchFieldId.of("hdr.ipv4.src_addr"), dip.toOctets());
            final PiAction piAction = PiAction.builder().withId(PiActionId.of("Ingress.upf_snat"))
                    .withParameter(new PiActionParam(PiActionParamId.of("upf_vip"), vip.toOctets()))
                    .build();
            final FlowRule flowRule = DefaultFlowRule.builder().fromApp(appId).forDevice(dev.id())
                    .forTable(PiTableId.of("Ingress.upf_snat_table")).makePermanent().withPriority(65535)
                    .withSelector(DefaultTrafficSelector.builder().matchPi(criterionBuilder.build()).build())
                    .withTreatment(DefaultTrafficTreatment.builder().piTableAction(piAction).build()).build();
            Semaphore sem = new Semaphore(1, false);
            sem.acquire();
            flowIdToSemaphore.put(flowRule.id(), sem);
            flowRuleService.applyFlowRules(flowRule);
            log.info("installUpfSnatTableRule: upfDip = {}, upfVip = {}", dip, vip);
            return flowRule;
        } catch (Exception e) {
            log.info(e.toString());
            return null;
        }
    }

    public synchronized FlowRule installIpRouteTableRule(Ip4Address ip, long port, MacAddress dmac) {
        try {
            final PiCriterion.Builder criterionBuilder = PiCriterion.builder()
                    .matchTernary(PiMatchFieldId.of("hdr.ipv4.dst_addr"), ip.toInt(), 0xffffffff);
            final PiAction piAction = PiAction.builder().withId(PiActionId.of("Ingress.send"))
                    .withParameter(new PiActionParam(PiActionParamId.of("port"), port))
                    .withParameter(new PiActionParam(PiActionParamId.of("smac"), virtualMac.toBytes()))
                    .withParameter(new PiActionParam(PiActionParamId.of("dmac"), dmac.toBytes())).build();
            final FlowRule flowRule = DefaultFlowRule.builder().fromApp(appId).forDevice(dev.id())
                    .forTable(PiTableId.of("Ingress.ip_route_table")).makePermanent().withPriority(65535)
                    .withSelector(DefaultTrafficSelector.builder().matchPi(criterionBuilder.build()).build())
                    .withTreatment(DefaultTrafficTreatment.builder().piTableAction(piAction).build()).build();
            Semaphore sem = new Semaphore(1, false);
            sem.acquire();
            flowIdToSemaphore.put(flowRule.id(), sem);
            flowRuleService.applyFlowRules(flowRule);
            log.info("installIpRouteTableRule: ip = {}, switchPort = {}, mac = {}", ip, port, dmac);
            return flowRule;
        } catch (Exception e) {
            log.info(e.toString());
            return null;
        }
    }

    public void waitFlowRulesActionComplete(FlowRule... rules) {
        try {
            for (FlowRule rule : rules) {
                flowIdToSemaphore.get(rule.id()).acquire();
                flowIdToSemaphore.remove(rule.id());
            }
        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    public void removeFlowRuleListener() {
        flowRuleService.removeListener(upflbFlowRuleListener);
    }

    public MacAddress getVirtualMac() {
        return virtualMac;
    }

    private class UpflbFlowRuleListener implements FlowRuleListener {
        @Override
        public void event(FlowRuleEvent event) {
            FlowRule rule = event.subject();
            if (rule.deviceId().equals(dev.id()) && rule.appId() == appId.id() && event.type().equals(RULE_ADDED)) {
                // log.info("ADDED of {}, {}, {}", rule.tableId(), rule.selector().criteria(),
                // rule.treatment().immediate());
                if (flowIdToSemaphore.containsKey(rule.id())) {
                    flowIdToSemaphore.get(rule.id()).release();
                }
            }
        }
    }
}
