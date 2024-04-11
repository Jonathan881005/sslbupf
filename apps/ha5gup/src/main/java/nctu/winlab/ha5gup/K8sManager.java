package nctu.winlab.ha5gup;

import java.io.*;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.slf4j.Logger;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.Exec;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Pod;

import static org.slf4j.LoggerFactory.getLogger;

public class K8sManager {
    protected static final Logger log = getLogger(K8sManager.class);

    private class K8sNode {
        public K8sNode(Long port, MacAddress mac) {
            this.port = port;
            this.mac = mac;
        }
        public Long port;
        public MacAddress mac;
    }

    private final HashMap<Ip4Address, K8sNode> nodes = new HashMap<Ip4Address, K8sNode>();
    private final HashMap<Ip4Address, String> nodeIpToHostAgentName = new HashMap<Ip4Address, String>();
    private CoreV1Api coreV1Api;

    public K8sManager() {
        try {
            // file path to your KubeConfig
            String kubeConfigPath = "/home/winlab/kube_config";
            // loading the out-of-cluster config, a kubeconfig from file-system
            ApiClient client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();
            // set the global default api-client to the in-cluster one from above
            Configuration.setDefaultApiClient(client);
            // the CoreV1Api loads default api-client from global configuration.
            coreV1Api = new CoreV1Api();

            initNodesLocation();
            getHostAgentNames();
            disableReversePathFiltering();
        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    public Long getNodePortNumber(Ip4Address nodeIp) {
        return nodes.get(nodeIp).port;
    }

    public MacAddress getNodeMacAddress(Ip4Address nodeIp) {
        return nodes.get(nodeIp).mac;
    }

    public String getHostIntfName(Ip4Address upfDip, Ip4Address nodeIp) {
        String cmd = String.format("ip route get %s | awk -F ' ' '{printf \"%%s\", $3}'", upfDip);
        String intfName = execHostRouteAgentCommand(nodeIp, cmd);
        log.info("Host intertface of {} is {}", upfDip, intfName);
        return intfName;
    }

    public synchronized String execHostRouteAgentCommand(Ip4Address nodeIp, String cmd) {
        return execPodCommand(nodeIpToHostAgentName.get(nodeIp), cmd);
    }

    public synchronized String execPodCommand(String podName, String cmd) {
        try {
            Exec exec = new Exec();
            final Process proc = exec.exec("default", podName, new String[] { "sh", "-c", cmd }, false, false);
            log.info("Command executed: {}", cmd);
            String result = new BufferedReader(new InputStreamReader(proc.getInputStream())).lines().collect(Collectors.joining("\n"));
            proc.waitFor();
            proc.destroy();
            return result;
        } catch (Exception e) {
            log.info(e.toString());
            return null;
        }
    }

    private void initNodesLocation() {
        nodes.put(Ip4Address.valueOf("192.168.13.201"), new K8sNode(Long.valueOf(35), MacAddress.valueOf("00:1b:21:bc:0e:a8")));
        nodes.put(Ip4Address.valueOf("192.168.13.202"), new K8sNode(Long.valueOf(16), MacAddress.valueOf("3c:fd:fe:a6:1d:88")));
        nodes.put(Ip4Address.valueOf("192.168.13.203"), new K8sNode(Long.valueOf(18), MacAddress.valueOf("00:1b:21:bc:0e:a6")));
        nodes.put(Ip4Address.valueOf("192.168.13.218"), new K8sNode(Long.valueOf(175), MacAddress.valueOf("3c:fd:fe:ba:f2:23")));
        nodes.put(Ip4Address.valueOf("192.168.13.219"), new K8sNode(Long.valueOf(174), MacAddress.valueOf("3c:fd:fe:ba:fa:d3")));
    }

    private void getHostAgentNames() {
        try {
            V1PodList podList = coreV1Api.listNamespacedPod("default", null, null, null, null, "app=host-route-agent", null, null, null, 10, false);
            for (V1Pod pod : podList.getItems()) {
                nodeIpToHostAgentName.put(Ip4Address.valueOf(pod.getStatus().getHostIP()), pod.getMetadata().getName());
                log.info("{} running on {}", pod.getMetadata().getName(), pod.getStatus().getHostIP());
            }
        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    private void disableReversePathFiltering() {
        for (String hostAgentName : nodeIpToHostAgentName.values()) {
            execPodCommand(hostAgentName, "iptables -t raw -D cali-PREROUTING $(iptables -t raw -L cali-PREROUTING --line-numbers | grep 'rpfilter invert' | cut -d ' ' -f 1)");
        }
    }
}
