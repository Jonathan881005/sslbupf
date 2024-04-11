package nctu.winlab.ha5gup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.PriorityQueue;

import org.onlab.packet.Ip4Address;
import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

public class UpfSelector {
    public enum UpfSelectorCriteria {
        UPF_SELECTOR_CRITERIA_POD_CPU_USAGE,
        UPF_SELECTOR_CRITERIA_NODE_CPU_USAGE,
        UPF_SELECTOR_CRITERIA_RECEIVING_THROUGHPUT;
    }

    protected static final Logger log = getLogger(UpfSelector.class);

    private UpfSelectorCriteria criteria;
    private HashMap<Ip4Address, HashMap<Ip4Address, UpfInstance>> upfIpToUpfInstance;

    public UpfSelector(UpfSelectorCriteria criteria, HashMap<Ip4Address, HashMap<Ip4Address, UpfInstance>> upfIpToUpfInstance) {
        this.criteria = criteria;
        this.upfIpToUpfInstance = upfIpToUpfInstance;
    }

    public Ip4Address selectUpf(Ip4Address upfVip) {
        switch (criteria) {
            case UPF_SELECTOR_CRITERIA_POD_CPU_USAGE:
                return selectUpfWithLeastPodCpuUsage(upfVip);
            case UPF_SELECTOR_CRITERIA_NODE_CPU_USAGE:
                return selectUpfWithLeastNodeCpuUsage(upfVip);
            case UPF_SELECTOR_CRITERIA_RECEIVING_THROUGHPUT:
                return selectUpfWithLeastReceivingThroughput(upfVip);
            default:
                log.error("Unsupported criteria: {}", criteria);
        }
        return null;
    }

    public HashMap<UpfInstance, Collection<PfcpSession>> allocateUpfsForSessions(Ip4Address upfVip, Collection<PfcpSession> sessions, UpfInstance originalUpf, double threshold) {
        PriorityQueue<UpfInstance> pq = new PriorityQueue<UpfInstance>();
        HashMap<UpfInstance, Collection<PfcpSession>> allocations = new HashMap<UpfInstance, Collection<PfcpSession>>();

        try {
            // (irate(container_network_receive_bytes_total{pod=~"upf.*|ulcl.*|psa.*", interface="eth0"}[60s]) * on (pod) group_left(pod_ip) kube_pod_info{pod=~"upf.*|ulcl.*|psa.*"}) * 8 / 1000 / 1000 / 1000
            String expr = "%28irate%28container_network_receive_bytes_total%7Bpod%3D%7E%22upf.*%7Culcl.*%7Cpsa.*%22%2C+interface%3D%22eth0%22%7D%5B60s%5D%29+*+on+%28pod%29+group_left%28pod_ip%29+kube_pod_info%7Bpod%3D%7E%22upf.*%7Culcl.*%7Cpsa.*%22%7D%29+*+8+%2F+1000+%2F+1000+%2F+1000";
            JsonNode respBody = queryPrometheus(expr);
            HashMap<Ip4Address, UpfInstance> upfInstances = upfIpToUpfInstance.get(upfVip);
            log.info("----------------------------------------------------------------");
            for (JsonNode pod : respBody.get("data").get("result")) {
                String name = pod.get("metric").get("pod").asText();
                Ip4Address ip = Ip4Address.valueOf(pod.get("metric").get("pod_ip").asText());
                double throughput = Double.valueOf(pod.get("value").get(1).asText()).doubleValue();
                boolean available = !ip.equals(originalUpf.ip()) && upfInstances.containsKey(ip);
                if (available) {
                    UpfInstance upf = upfInstances.get(ip);
                    upf.updateThroughput(throughput);
                    pq.offer(upf);
                }
                log.info("{} ({}), {}{}", name, ip, throughput, available ? "" : " (X)");
            }
            log.info("----------------------------------------------------------------");

            UpfInstance upf = null;
            double quota = 0;
            Collection<PfcpSession> subsessions = null;
            for (PfcpSession session : sessions) {
                if (session.throughput() >= quota) {
                    if (pq.isEmpty()) {
                        log.info("There aren't enough UPFs to take over all sessions, maybe system should scale-out");
                        break;
                    }
                    upf = pq.poll();
                    quota = threshold - upf.throughput();
                    subsessions = new ArrayList<PfcpSession>();
                    allocations.put(upf, subsessions);
                }
                quota -= session.throughput();
                subsessions.add(session);
            }
        } catch (Exception e) {
            log.info(e.toString());
        }
        return allocations;
    }

    public double getUpfThroughput(String upfName) {
        double throughput = 0;
        try {
            // (irate(container_network_receive_bytes_total{pod="${upfName}", interface="eth0"}[60s]) * on (pod) group_left(pod_ip) kube_pod_info{pod="${upfName}") * 8 / 1000 / 1000 / 1000
            String expr = "%28irate%28container_network_receive_bytes_total%7Bpod%3D%22" +
                    upfName +
                    "%22%2C+interface%3D%22eth0%22%7D%5B60s%5D%29+*+on+%28pod%29+group_left%28pod_ip%29+kube_pod_info%7Bpod%3D%22" +
                    upfName +
                    "%22%7D%29+*+8+%2F+1000+%2F+1000+%2F+1000";
            JsonNode respBody = queryPrometheus(expr);

            // The result should only contain an object.
            JsonNode pod = respBody.get("data").get("result").get(0);
            String name = pod.get("metric").get("pod").asText();
            Ip4Address ip = Ip4Address.valueOf(pod.get("metric").get("pod_ip").asText());
            throughput = Double.valueOf(pod.get("value").get(1).asText()).doubleValue();
            log.info("{} ({}), throughput: {}", name, ip, throughput);
        } catch (Exception e) {
            log.info(e.toString());
        }
        return throughput;
    }

    private Ip4Address selectUpfWithLeastPodCpuUsage(Ip4Address upfVip) {
        // rate(container_cpu_usage_seconds_total{container=~'upf.*'}[20s]) * on(pod) group_left(pod_ip) kube_pod_info{pod=~"upf.*"}
        String expr = "rate%28container_cpu_usage_seconds_total%7Bcontainer%3D%7E%27upf.*%27%7D%5B20s%5D%29+*+on%28pod%29+group_left%28pod_ip%29+kube_pod_info%7Bpod%3D%7E%22upf.*%22%7D";
        return selectUpfWithLeastResourceUsage(upfVip, expr);
    }

    private Ip4Address selectUpfWithLeastNodeCpuUsage(Ip4Address upfVip) {
        // label_replace(rate(node_cpu_seconds_total{mode="softirq"}[20s]), "host_ip", "$1", "instance", "(.*):.*") * on(host_ip) group_left(namespace, pod, pod_ip) kube_pod_info{pod=~"upf.*"}
        String expr = "label_replace%28rate%28node_cpu_seconds_total%7Bmode%3D%22softirq%22%7D%5B20s%5D%29%2C+%22host_ip%22%2C+%22%241%22%2C+%22instance%22%2C+%22%28.*%29%3A.*%22%29+*+on%28host_ip%29+group_left%28namespace%2C+pod%2C+pod_ip%29+kube_pod_info%7Bpod%3D%7E%22upf.*%22%7D";
        return selectUpfWithLeastResourceUsage(upfVip, expr);
    }

    private Ip4Address selectUpfWithLeastReceivingThroughput(Ip4Address upfVip) {
        // (irate(container_network_receive_bytes_total{pod=~"upf.*|ulcl.*|psa.*", interface="eth0"}[60s]) * on (pod) group_left(pod_ip) kube_pod_info{pod=~"upf.*|ulcl.*|psa.*"}) * 8 / 1000 / 1000 / 1000
        String expr = "%28irate%28container_network_receive_bytes_total%7Bpod%3D%7E%22upf.*%7Culcl.*%7Cpsa.*%22%2C+interface%3D%22eth0%22%7D%5B60s%5D%29+*+on+%28pod%29+group_left%28pod_ip%29+kube_pod_info%7Bpod%3D%7E%22upf.*%7Culcl.*%7Cpsa.*%22%7D%29+*+8+%2F+1000+%2F+1000+%2F+1000";
        return selectUpfWithLeastResourceUsage(upfVip, expr);
    }

    private Ip4Address selectUpfWithLeastResourceUsage(Ip4Address upfVip, String queryExpr) {
        BigDecimal minUsage = null;
        Ip4Address minUsageUpfDip = null;
        try {
            JsonNode respBody = queryPrometheus(queryExpr);
            HashMap<Ip4Address, UpfInstance> upfInstances = upfIpToUpfInstance.get(upfVip);
            log.info("----------------------------------------------------------------");
            for (JsonNode pod : respBody.get("data").get("result")) {
                String name = pod.get("metric").get("pod").asText();
                Ip4Address ip = Ip4Address.valueOf(pod.get("metric").get("pod_ip").asText());
                BigDecimal metric = new BigDecimal(pod.get("value").get(1).asText());
                boolean available = upfInstances.containsKey(ip);

                if (available && (minUsage == null || metric.compareTo(minUsage) < 0)) {
                    minUsage = metric;
                    minUsageUpfDip = ip;
                }
                log.info("pod: {} ({}), metric: {}{}", name, ip, metric, available ? "" : " (X)");
            }
            log.info("----------------------------------------------------------------");
        } catch (Exception e) {
            log.info(e.toString());
        }
        // If metrics for UPFs are not available, return the first one.
        if (minUsageUpfDip == null) {
            minUsageUpfDip = upfIpToUpfInstance.get(upfVip).values().iterator().next().ip();
        }
        log.info("Select {}", minUsageUpfDip);
        return minUsageUpfDip;
    }

    private JsonNode queryPrometheus(String queryExpr) {
        JsonNode respBody = null;
        try {
            URI uri = URI.create("http://localhost:60909/api/v1/query?query=" + queryExpr);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(uri).header("accept", "application/json").build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            respBody = new ObjectMapper().readTree(response.body());
        } catch (Exception e) {
            log.info(e.toString());
        }
        return respBody;
    }
}
