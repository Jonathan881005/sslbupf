#!/usr/bin/env bash

LIBGTP5GNL_PATH="/home/winlab/libgtp5gnl/script"
UERANSIM_PATH="/home/winlab/ueransim-3.2.4"

REMOTE_ONOS_HOSTNAME="winlab@140.113.131.175"
# More succinct, without line number information.
REMOTE_ONOS_LOGS_1_PATH="/tmp/onos-2.2.2/onos.log"
# Identical to what ONOS outputs.
REMOTE_ONOS_LOGS_2_PATH="/tmp/onos-2.2.2/apache-karaf-4.2.8/data/log/karaf.log"

EVALUATION_PATH=$(dirname $(realpath ${0})i)
LOGS_PATH=${EVALUATION_PATH}/$(date +"%Y-%m-%d-%H:%M:%S")

ROUND_COUNT=4
SESSION_MULTIPLIER=1

#${LIBGTP5GNL_PATH}/ulcl-free5gc-gnb.sh

sudo -v
mkdir ${LOGS_PATH}

for round in $(seq 1 ${ROUND_COUNT}); do
	for ((i=${SESSION_MULTIPLIER}*(${round}-1)+1; i<=${SESSION_MULTIPLIER}*${round}; i++)); do
		echo "Start free5gc-ue${i}"
		set -x
		sudo -b ${UERANSIM_PATH}/build/nr-ue -c ${UERANSIM_PATH}/config/free5gc-ues/free5gc-ue${i}.yaml --no-routing-config
		sudo ip netns exec dn iperf3 -s -p 520${i} --timestamp > ${LOGS_PATH}/ue${i}-server.log 2>&1 &
		set +x
		# sleep 6
	done

	sleep 12
	for ((i=${SESSION_MULTIPLIER}*(${round}-1)+1; i<=${SESSION_MULTIPLIER}*${round}; i++)); do
		set -x
		iperf3 -c 10.0.1.103 -p 520${i} -B 60.60.0.${i} -u -b 2G -l 1300 -t 100 --timestamp > ${LOGS_PATH}/ue${i}-client.log 2>&1 &
		set +x
		# sleep 6
	done

	if [ ${round} -lt ${ROUND_COUNT} ]; then
		echo "Round $((${round}+1)) countdown: "
		for c in {8..1}; do
			echo -ne "\r  \r${c}"
			sleep 1
		done
		echo ""
	fi
done

echo "Termination countdown: "
for c in {120..1}; do
	echo -ne "\r   \r${c}"
	sleep 1
done

sudo pkill iperf3
sudo pkill nr-ue

ssh ${REMOTE_ONOS_HOSTNAME} "tac ${REMOTE_ONOS_LOGS_1_PATH} | sed '/Application nctu.winlab.ha5gup has been activated/q' | tac" > ${LOGS_PATH}/onos1.log
ssh ${REMOTE_ONOS_HOSTNAME} "tac ${REMOTE_ONOS_LOGS_2_PATH} | sed '/Application nctu.winlab.ha5gup has been activated/q' | tac" > ${LOGS_PATH}/onos2.log

#${LIBGTP5GNL_PATH}/cleanup.sh
