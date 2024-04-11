#!/usr/bin/env bash

parse_argument () {
	CORE_NETWORK=${1}
	PROTOCOL=${2}
	THROUGHPUT=${3}
	PACKET_SIZE=${4}
	MODE=${5}

	EVALUATION_PATH=$(dirname $(realpath ${BASH_SOURCE}))
	LOGS_BASE_PATH=${EVALUATION_PATH}/${CORE_NETWORK}-${PROTOCOL}-${THROUGHPUT}-${PACKET_SIZE}bytes/${MODE}
	LOGS_PATH=${LOGS_BASE_PATH}/$(date +"%Y-%m-%d-%H:%M:%S")
	UERANSIM_PATH="/home/winlab/ueransim-3.2.4"

	REMOTE_ONOS_HOSTNAME="winlab@140.113.131.175"
	# More succinct, without line number information.
	REMOTE_ONOS_LOGS_1_PATH="/tmp/onos-2.2.2/onos.log"
	# Identical to what ONOS outputs.
	REMOTE_ONOS_LOGS_2_PATH="/tmp/onos-2.2.2/apache-karaf-4.2.8/data/log/karaf.log"

	mkdir -p ${LOGS_BASE_PATH}/archived
	mv ${LOGS_BASE_PATH}/* ${LOGS_BASE_PATH}/archived
	mkdir -p ${LOGS_PATH}

	if [[ ${CORE_NETWORK} == *free5gc* ]]; then
		UE_CONFIG_PATH_PREFIX=${UERANSIM_PATH}/config/free5gc-ues/free5gc-ue
		UE1_IP="60.60.0.1"
		UE2_IP="60.60.0.2"
	else
		UE_CONFIG_PATH_PREFIX=${UERANSIM_PATH}/config/open5gs-ues/open5gs-ue
		UE1_IP="60.60.0.2"
		UE2_IP="60.60.0.3"
	fi

	if [[ ${PROTOCOL} == tcp ]]; then
		IPERF3_ARGS="-M ${PACKET_SIZE}"
	else
		IPERF3_ARGS="-u -l ${PACKET_SIZE}"
	fi
}

establish_sessions () {
	set -x
	sudo ${UERANSIM_PATH}/build/nr-ue -c ${UE_CONFIG_PATH_PREFIX}1.yaml --no-routing-config &
	sleep 5
	sudo ${UERANSIM_PATH}/build/nr-ue -c ${UE_CONFIG_PATH_PREFIX}2.yaml --no-routing-config &
	sleep 5
	set +x
}

send_packets () {
	set -x
	sudo ip netns exec dn iperf3 -s -p 5201 --timestamp > ${LOGS_PATH}/ue1-server.log 2>&1 &
	sudo ip netns exec dn iperf3 -s -p 5202 --timestamp > ${LOGS_PATH}/ue2-server.log 2>&1 &
	sleep 2
	iperf3 -c 10.0.1.103 -p 5201 -B ${UE1_IP} -b ${THROUGHPUT} ${IPERF3_ARGS} -t 30 --timestamp > ${LOGS_PATH}/ue1-client.log 2>&1 &
	iperf3 -c 10.0.1.103 -p 5202 -B ${UE2_IP} -b ${THROUGHPUT} ${IPERF3_ARGS} -t 30 --timestamp > ${LOGS_PATH}/ue2-client.log 2>&1 &
	set +x

	sleep 15
	set -x
	kubectl delete deployments upf1 | ts "%Y-%m-%d-%H:%M:%S"
	set +x

	for c in {20..1}; do
		echo -ne "\r  \r${c}"
		sleep 1
	done
	echo ""

	ssh ${REMOTE_ONOS_HOSTNAME} "tac ${REMOTE_ONOS_LOGS_1_PATH} | sed '/Application nctu.winlab.ha5gup has been activated/q' | tac" > ${LOGS_PATH}/onos1.log
	ssh ${REMOTE_ONOS_HOSTNAME} "tac ${REMOTE_ONOS_LOGS_2_PATH} | sed '/Application nctu.winlab.ha5gup has been activated/q' | tac" > ${LOGS_PATH}/onos2.log
}

cleanup () {
	sudo pkill iperf3
	sudo pkill nr-ue
}

print_logs () {
	if [[ ${PROTOCOL} == tcp ]]; then
		cat ${LOGS_PATH}/ue1-client.log
	else
		cat ${LOGS_PATH}/ue1-server.log
		cat ${LOGS_PATH}/ue1-server.log | sed 's/.*(\(.*\)%)/\1/'
	fi
}

if [ ${#} -ne 5 ]; then
	echo "Usage: ./test.sh <CORE_NETWORK> <PROTOCOL> <THROUGHPUT> <PACKET_SIZE> <MODE>"
	exit 1
fi

trap cleanup SIGINT

sudo -v

parse_argument ${1} ${2} ${3} ${4} ${5}
establish_sessions
send_packets
cleanup
print_logs
