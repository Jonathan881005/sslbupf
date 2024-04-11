#!/usr/bin/env bash

if [ ${#} -ne 2 ]; then
	echo "Usage: ${0} <START_TIMESTAMP> <END_TIMESTAMP>"
	exit 1
fi

START_TIMESTAMP=${1}
END_TIMESTAMP=${2}

set -x

./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'irate(container_network_receive_bytes_total{pod=~"ulcl1.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"ulcl1.*"} * 8 / 1000 / 1000 / 1000' > ulcl1-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'sum(irate(container_network_receive_bytes_total{pod=~"ulcl1.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"ulcl1.*"} * 8 / 1000 / 1000 / 1000) / count(kube_pod_info{pod=~"ulcl1.*"})' > avg-ulcl1-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'irate(container_network_receive_bytes_total{pod=~"psa1.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"psa1.*"} * 8 / 1000 / 1000 / 1000' > psa1-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'sum(irate(container_network_receive_bytes_total{pod=~"psa1.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"psa1.*"} * 8 / 1000 / 1000 / 1000) / count(kube_pod_info{pod=~"psa1.*"})' > avg-psa1-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'irate(container_network_receive_bytes_total{pod=~"psa2.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"psa2.*"} * 8 / 1000 / 1000 / 1000' > psa2-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'sum(irate(container_network_receive_bytes_total{pod=~"psa2.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"psa2.*"} * 8 / 1000 / 1000 / 1000) / count(kube_pod_info{pod=~"psa2.*"})' > avg-psa2-pod-receiving-throughput.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'count(kube_pod_info{pod=~"ulcl1.*"})' > ulcl1-nums.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'ceil(count(kube_pod_info{pod=~"ulcl1.*"}) * ((sum(irate(container_network_receive_bytes_total{pod=~"ulcl1.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"ulcl1.*"} * 8 / 1000 / 1000 / 1000) / count(kube_pod_info{pod=~"ulcl1.*"})) / 1.5))' > ulcl1-desired-nums.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'count(kube_pod_info{pod=~"psa1.*"})' > psa1-nums.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'ceil(count(kube_pod_info{pod=~"psa1.*"}) * ((sum(irate(container_network_receive_bytes_total{pod=~"psa1.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"psa1.*"} * 8 / 1000 / 1000 / 1000) / count(kube_pod_info{pod=~"psa1.*"})) / 1.5))' > psa1-desired-nums.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'count(kube_pod_info{pod=~"psa2.*"})' > psa2-nums.csv
./query_csv.py ${START_TIMESTAMP} ${END_TIMESTAMP} 'ceil(count(kube_pod_info{pod=~"psa2.*"}) * ((sum(irate(container_network_receive_bytes_total{pod=~"psa2.*", interface="eth0"}[60s]) * on (pod) kube_pod_info{pod=~"psa2.*"} * 8 / 1000 / 1000 / 1000) / count(kube_pod_info{pod=~"psa2.*"})) / 1.5))' > psa2-desired-nums.csv
paste -d , ulcl1-pod-receiving-throughput.csv avg-ulcl1-pod-receiving-throughput.csv psa1-pod-receiving-throughput.csv avg-psa1-pod-receiving-throughput.csv psa2-pod-receiving-throughput.csv avg-psa2-pod-receiving-throughput.csv ulcl1-nums.csv ulcl1-desired-nums.csv psa1-nums.csv psa1-desired-nums.csv psa2-nums.csv psa2-desired-nums.csv
sed 's/\r//g'
