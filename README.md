# SDNFV-enabled Scalability and Load Balancing Architecture for 5G User Plane Function

## Architecture

The system consists of at least four nodes and a P4 switch.
* `ONOS`: Run the ONOS controller, which runs the LB-C.
* `CP`: Run K8s control plane, Prometheus, 5GC control plane etc.
* `UP`: Run 5GC user plane. For multiple UPF scenario, we need three `UP`s.
* `TG`: Traffic generator, serves as UE and DN.
* `P4`: The P4 switch run the LB-U.

## Installation

### `CP`

1. Deploy K8s control plane and install necessary tools
```sh
./deploy.sh master ${NODE_NAME} ${MANAGEMENT_IP}
```

### `UP`

1. Execute `kubeadm token create --print-join-command` in master node to get token and ca-cert-hash
2. Assign token and ca-cert-hash to variables in `deploy_worker()` in `deploy.sh`
3. Make a K8s worker node and install necessary tools
```sh
./deploy.sh worker ${NODE_NAME} ${MANAGEMENT_IP} ${MASTER_MANAGEMENT_IP}
```

### `ONOS`

1. Install ONOS v2.2.2
   * https://wiki.onosproject.org/display/ONOS/Developer+Quick+Start
2. Put KubeConfig to `kubeConfigPath`, which is hardcoded in `K8sManager.java`
   * If you would like to add `ONOS` to the K8s cluster (which is preferable), you can rewrite the `K8sManager` constructor with [this example](https://github.com/kubernetes-client/java/blob/release-12.0.1/examples/examples-release-12/src/main/java/io/kubernetes/client/examples/InClusterClientExample.java) and eliminate the local KubeConfig
3. Build the P4C image
```sh
git clone https://gitlab.com/nctuwinlab/winlab-p4/p4-dev-images.git
cd p4-dev-images/onos-2.2.2-bf9.1.0-p4c
docker build -t winlab4p4/onos-2.2.2-p4c:bf-9.1.0 .
```

### `TG`

1. Build and install the patched gtp5g
   * The patch is a workaround for evaluating Open5GS. If only free5GC is needed, official gtp5g works well
```sh
sudo apt-get -y install build-essential
git clone https://github.com/briansp8210/gtp5g.git
cd gtp5g && make && sudo make install
```

### `P4`

1. Currently

## Build

## Evaluation

### Throughput

### Scaling

### Rebalancing

### Seamless Migration

### Multiple UPFs Scaling
