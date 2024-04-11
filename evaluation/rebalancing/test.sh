#!/usr/bin/env bash

parse_argument () {
	CORE_NETWORK=${1}
	PROTOCOL=${2}
	UPF_NUMS=${3}

	EVALUATION_PATH=$(dirname $(realpath ${0}))
	LOGS_BASE_PATH=${EVALUATION_PATH}/${CORE_NETWORK}-${PROTOCOL}-${UPF_NUMS}upf
	UERANSIM_PATH="/home/winlab/ueransim-3.2.4"

	REMOTE_ONOS_HOSTNAME="winlab@140.113.131.175"
	# More succinct, without line number information.
	REMOTE_ONOS_LOGS_1_PATH="/tmp/onos-2.2.2/onos.log"
	# Identical to what ONOS outputs.
	REMOTE_ONOS_LOGS_2_PATH="/tmp/onos-2.2.2/apache-karaf-4.2.8/data/log/karaf.log"

	if [[ ${CORE_NETWORK} == free5gc ]]; then
		UE_CONFIG_PATH_PREFIX=${UERANSIM_PATH}/config/free5gc-ues/free5gc-ue
	else
		UE_CONFIG_PATH_PREFIX=${UERANSIM_PATH}/config/open5gs-ues/open5gs-ue
	fi

	if [[ ${PROTOCOL} == tcp ]]; then
		IPERF3_ARGS="-M 1300"
		if [ ${#} -eq 4 ]; then
			IPERF3_ARGS="${IPERF3_ARGS} -b ${4}"
			LOGS_BASE_PATH=${LOGS_BASE_PATH}-${4}
		fi
	else
		if [ ${#} -lt 4 ]; then
			echo "Missing throughput for UDP flow"
			exit 1
		fi
		IPERF3_ARGS="-u -l 1300 -b ${4}"
		LOGS_BASE_PATH=${LOGS_BASE_PATH}-${4}
	fi

	LOGS_PATH=${LOGS_BASE_PATH}/$(date +"%Y-%m-%d-%H:%M:%S")
	mkdir -p ${LOGS_BASE_PATH}/archived
	mv ${LOGS_BASE_PATH}/* ${LOGS_BASE_PATH}/archived
	mkdir -p ${LOGS_PATH}
}

establish_sessions () {
	MAX_UPF_NUMS=4
	if ! pgrep nr-ue; then
		for i in $(seq 1 ${MAX_UPF_NUMS}); do
			set -x
			sudo ${UERANSIM_PATH}/build/nr-ue -c ${UE_CONFIG_PATH_PREFIX}${i}.yaml --no-routing-config &
			sleep 5
			set +x
		done
	fi
}

send_packets () {
	TIMESPAN=60
	INTERMISSION=0
	for i in $(seq 1 ${UPF_NUMS}); do
		set -x
		sudo ip netns exec dn iperf3 -s -p 520${i} --timestamp --format=g > ${LOGS_PATH}/ue${i}-server.log 2>&1 &
		# ssh winlab@192.168.13.215 iperf3 -s -p 520${i} --timestamp --daemon --logfile ~/ue${i}-server.log
		set +x
	done
	sleep 2
	for i in $(seq 1 ${UPF_NUMS}); do
		if [[ ${CORE_NETWORK} == free5gc ]]; then UE_IP="60.60.0.${i}"; else UE_IP="60.60.0.$((${i}+1))"; fi
		set -x
		iperf3 -c 10.0.1.103 -p 520${i} -B ${UE_IP} ${IPERF3_ARGS} -t ${TIMESPAN} --timestamp --format=g > ${LOGS_PATH}/ue${i}-client.log 2>&1 &
		# iperf3 -c 10.0.1.115 -p 520${i} -B ${UE_IP} ${IPERF3_ARGS} -t $((${TIMESPAN}+${INTERMISSION}*(${UPF_NUMS}-${i}))) --timestamp > ${LOGS_PATH}/ue${i}-client.log 2>&1 &
		set +x
		# if [ ${i} -ne ${UPF_NUMS} ]; then
		# 	sleep ${INTERMISSION}
		# fi
	done

	for ((c=$((${TIMESPAN}+5)); c>0; c--)); do
		echo -ne "\r  \r${c}"
		sleep 1
	done
	echo ""
}

cleanup () {
	sudo pkill iperf3
	sudo pkill nr-ue
	# ssh winlab@192.168.13.215 pkill iperf3
	# for i in $(seq 1 ${UPF_NUMS}); do
	# 	scp winlab@192.168.13.215:~/ue${i}-server.log ${LOGS_PATH}
	# done
	# ssh winlab@192.168.13.215 rm ~/ue*.log
}

print_logs () {
	echo "Logs path: ${LOGS_PATH}"
	SUM=0
	for i in $(seq 1 ${UPF_NUMS}); do
		RESULT=$(grep receiver ${LOGS_PATH}/ue${i}-server.log)
		echo ${RESULT}
		SUM=$(python -c "print ${SUM}+$(echo ${RESULT} | awk '{print $12}')")
	done
	echo "${SUM}"
}

gather_onos_logs () {
	ssh ${REMOTE_ONOS_HOSTNAME} "tac ${REMOTE_ONOS_LOGS_1_PATH} | sed '/Application nctu.winlab.ha5gup has been activated/q' | tac" > ${LOGS_PATH}/onos1.log
	ssh ${REMOTE_ONOS_HOSTNAME} "tac ${REMOTE_ONOS_LOGS_2_PATH} | sed '/Application nctu.winlab.ha5gup has been activated/q' | tac" > ${LOGS_PATH}/onos2.log
}


if [ ${#} -lt 3 ]; then
	echo "Usage: ./test.sh <CORE_NETWORK> <PROTOCOL> <UPF_NUMS> [UDP_THROUGHPUT]"
	exit 1
fi

trap cleanup SIGINT

sudo -v

parse_argument ${1} ${2} ${3} ${4}
establish_sessions
send_packets
cleanup
print_logs
gather_onos_logs
