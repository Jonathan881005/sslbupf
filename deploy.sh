#!/usr/bin/env bash

install_docker() {
	# https://docs.docker.com/engine/install/ubuntu/
	sudo apt-get update
	sudo apt-get install -y apt-transport-https ca-certificates curl gnupg lsb-release
	curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
	echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu \$(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
	sudo apt-get update
	sudo apt-get install -y docker-ce docker-ce-cli containerd.io

	# https://docs.docker.com/engine/install/linux-postinstall/#manage-docker-as-a-non-root-user
	sudo groupadd docker
	sudo usermod -aG docker $USER
}

install_kubeadm() {
	# Disable swap permanently for Kubernetes
	# Relative discussion can be found here: https://github.com/kubernetes/kubernetes/issues/53533
	sudo swapoff -a
	sudo sed -i 's/.*swap.*/#&/' /etc/fstab

	sudo apt-get update && sudo apt-get install -y apt-transport-https curl
	curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | sudo apt-key add -
	echo "deb https://apt.kubernetes.io/ kubernetes-xenial main" | sudo tee /etc/apt/sources.list.d/kubernetes.list
	sudo apt-get update
	sudo apt-get install -y kubelet=1.19.0-00 kubeadm=1.19.0-00 kubectl=1.19.0-00
	sudo apt-mark hold kubelet kubeadm kubectl
	echo "source <(kubectl completion bash)" >> ~/.bashrc
}

install_helm() {
	curl https://baltocdn.com/helm/signing.asc | sudo apt-key add -
	sudo apt-get install apt-transport-https -y
	echo "deb https://baltocdn.com/helm/stable/debian/ all main" | sudo tee /etc/apt/sources.list.d/helm-stable-debian.list
	sudo apt-get update
	sudo apt-get install helm -y
	helm completion bash | sudo tee /etc/bash_completion.d/helm > /dev/null
}

install_nfs_server() {
	sudo apt-get install -qqy nfs-kernel-server
	sudo mkdir -p /var/share/free5gc /var/share/open5gs
	echo "/var/share/free5gc *(rw,sync,no_root_squash)" | sudo tee -a /etc/exports
	echo "/var/share/open5gs *(rw,sync,no_root_squash)" | sudo tee -a /etc/exports
	sudo exportfs -r
	sudo showmount -e
}

install_calicoctl_and_apply_settings() {
	MANAGEMENT_IP=${1}

	wget https://github.com/projectcalico/calicoctl/releases/download/v3.12.3/calicoctl -q -O calicoctl
	chmod +x calicoctl
	sudo mv calicoctl /usr/local/bin
	sudo mkdir -p /etc/calico
	sudo tee /etc/calico/calicoctl.cfg > /dev/null <<- EOT
	apiVersion: projectcalico.org/v3
	kind: CalicoAPIConfig
	metadata:
	spec:
	  datastoreType: "kubernetes"
	  kubeconfig: "$HOME/.kube/config"
	EOT
	until calicoctl get felixconfiguration default; do
		sleep 1
	done

	kubectl set env daemonset/calico-node -n kube-system IP_AUTODETECTION_METHOD=can-reach=${MANAGEMENT_IP}
	calicoctl patch felixconfiguration default -p '{"spec":{"routeRefreshInterval": "0s"}}'
	calicoctl patch felixconfiguration default -p '{"spec":{"removeExternalRoutes": false}}'
	calicoctl patch felixconfiguration default -p '{"spec":{"iptablesRefreshInterval": "0s"}}'
	calicoctl patch ippool default-ipv4-ippool -p '{"spec":{"ipipMode": "CrossSubnet"}}'
	calicoctl apply -f ./install/infra/upfvip-cidr.yaml
	calicoctl apply -f ./install/infra/hep.yaml
}

install_gtp5g() {
	sudo apt-get -y install build-essential
	git clone https://github.com/PrinzOwO/gtp5g.git
	cd gtp5g && git checkout v0.2.1 && make && sudo make install
}

deploy_host_route_agent() {
	kubectl apply -f ./install/infra/daemonset.yaml
}

deploy_kube_prometheus_stack() {
	helm install --create-namespace -n monitoring kube-prom-stack ./install/kube-prometheus-stack
	kubectl apply -f ./install/infra/single-upf-service-dashboard.yaml
	kubectl apply -f ./install/infra/multi-upf-services-dashboard.yaml
}

config_kernel_networking() {
	echo "net.ipv4.ip_forward=1" | sudo tee -a /etc/sysctl.conf
	echo "net.ipv4.conf.all.rp_filter=0" | sudo tee -a /etc/sysctl.conf
	echo "net.ipv4.conf.default.rp_filter=0" | sudo tee -a /etc/sysctl.conf

	# Be sure to change the interfaces' name to yours environment!
	echo "net.ipv4.conf.enp0s8.rp_filter=0" | sudo tee -a /etc/sysctl.conf
	echo "net.ipv4.conf.enp0s9.rp_filter=0" | sudo tee -a /etc/sysctl.conf
	echo "net.ipv4.conf.enp0s10.rp_filter=0" | sudo tee -a /etc/sysctl.conf

	sudo sysctl -p
	# TODO: Enlarge MTU properly
}

deploy_master() {
	NODE_NAME="$1"
	MANAGEMENT_IP="$2"

	config_kernel_networking
	install_docker
	install_kubeadm
	install_helm
	install_nfs_server

	cat <<- EOT > kubeadm-config.yaml
	apiVersion: kubeadm.k8s.io/v1beta2
	kind: InitConfiguration
	nodeRegistration:
	  name: "$NODE_NAME"
	  taints: []
	  kubeletExtraArgs:
	    node-ip: "$MANAGEMENT_IP"
	localAPIEndpoint:
	  advertiseAddress: "$MANAGEMENT_IP"
	---
	apiVersion: kubeadm.k8s.io/v1beta2
	kind: ClusterConfiguration
	kubernetesVersion: v1.19.0
	apiServer:
	  extraArgs:
	    feature-gates: "SCTPSupport=true"
	  certSANs:
	  - "$MANAGEMENT_IP"
	controllerManager:
	  extraArgs:
	    horizontal-pod-autoscaler-sync-period: 1s
	networking:
	  podSubnet: "192.168.0.0/16"
	EOT
	sudo kubeadm init --config kubeadm-config.yaml
	mkdir -p "$HOME"/.kube
	sudo cp -i /etc/kubernetes/admin.conf "$HOME"/.kube/config
	sudo chown "$(id -u)":"$(id -g)" "$HOME"/.kube/config
	kubectl apply -f https://docs.projectcalico.org/archive/v3.12/manifests/calico.yaml
	kubeadm token create --print-join-command 2> /dev/null > /vagrant/join-command.tmp

	# Grant the default service account view permissions.
	# Ref: https://github.com/kubernetes/client-go/tree/v0.19.0/examples/in-cluster-client-configuration
	kubectl create clusterrolebinding default-view --clusterrole=view --serviceaccount=default:default

	install_calicoctl_and_apply_settings ${MANAGEMENT_IP}

	deploy_kube_prometheus_stack
	deploy_host_route_agent
}

deploy_worker() {
	NODE_NAME="$1"
	MANAGEMENT_IP="$2"
	MASTER_MANAGEMENT_IP="$3"

	# Please execute `kubeadm token create --print-join-command` in master node to get token and ca-cert-hash.
	# Assign them to following variables respectively.
	TOKEN=""
	CA_CERT_HASHES=""

	config_kernel_networking
	install_docker
	install_kubeadm
	install_gtp5g

	cat <<- EOT > kubeadm-config.yaml
	apiVersion: kubeadm.k8s.io/v1beta2
	kind: JoinConfiguration
	nodeRegistration:
	  name: "$NODE_NAME"
	  taints:
	  - key: "dataplane"
	    value: "true"
	    effect: "NoSchedule"
	  kubeletExtraArgs:
	    node-ip: "$MANAGEMENT_IP"
	    housekeeping-interval: 1s
	discovery:
	  bootstrapToken:
        apiServerEndpoint: "$MASTER_MANAGEMENT_IP:6443"
        token: "$TOKEN"
        caCertHashes:
        - "$CA_CERT_HASHES"
	EOT
	sudo kubeadm join --config kubeadm-config.yaml
}

exit_with_help_message() {
	echo "Usage: $0 master <NODE_NAME> <MANAGEMENT_IP>"
	echo "       $0 worker <NODE_NAME> <MANAGEMENT_IP> <MASTER_MANAGEMENT_IP>"
	exit 1
}

if [ "$1" == "master" ]; then
	if [ "$#" -ne 3 ]; then
		exit_with_help_message
	fi
	deploy_master "$2" "$3"
elif [ "$1" == "worker" ]; then
	if [ "$#" -ne 4 ]; then
		exit_with_help_message
	fi
	deploy_worker "$2" "$3" "$4"
else
	exit_with_help_message
fi
