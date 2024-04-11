#!/usr/bin/env bash

if [ ${#} -ne 2 ]; then
	echo "Usage: ${0} <START_TIMESTAMP> <END_TIMESTAMP>"
	exit 1
fi

START_TIMESTAMP=${1}
END_TIMESTAMP=${2}

set -x

./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'irate(container_network_receive_bytes_total{pod=~"upf.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"upf.*"} * 8 / 1000 / 1000 / 1000' 'pod' > rebalancing-test.csv
