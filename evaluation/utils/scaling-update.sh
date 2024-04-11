#!/usr/bin/env bash

if [ ${#} -ne 2 ]; then
	echo "Usage: ${0} <START_TIMESTAMP> <END_TIMESTAMP>"
	exit 1
fi

START_TIMESTAMP=${1}
END_TIMESTAMP=${2}

set -x

./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'irate(container_network_receive_bytes_total{pod=~"upf.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"upf.*"} * 8 / 1024 / 1024 / 1024' > upf-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'sum(irate(container_network_receive_bytes_total{pod=~"upf.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"upf.*"} * 8 / 1024 / 1024 / 1024) / count(kube_pod_info{pod=~"upf.*"})' > avg-upf-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'count(kube_pod_info{pod=~"upf.*"})' > upf-nums.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'ceil(count(kube_pod_info{pod=~"upf.*"}) * ((sum(irate(container_network_receive_bytes_total{pod=~"upf.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"upf.*"} * 8 / 1024 / 1024 / 1024) / count(kube_pod_info{pod=~"upf.*"})) / 1.5))' > desired-upf-nums.csv
paste -d , upf-pod-receiving-throughput.csv avg-upf-pod-receiving-throughput.csv upf-nums.csv desired-upf-nums.csv
sed 's/\r//g'
