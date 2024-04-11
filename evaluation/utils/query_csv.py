#!/usr/bin/env python3

import csv
import math
import requests
import sys
import subprocess
import time

"""
A simple program to print the result of a Prometheus query as CSV.
Modified from https://github.com/RobustPerception/python_examples/blob/master/csv/query_csv.py
"""

if len(sys.argv) != 5:
    print('Usage: {0} <START_TIME> <END_TIME> <QUERY> <LABEL>'.format(sys.argv[0]))
    sys.exit(1)

prometheus_sip = subprocess.check_output(['bash', '-c', "kubectl get svc -n monitoring | grep kube-prom-stack-kube-prome-prometheus | awk '{print $3}'"]).decode("utf-8").strip()
prometheus_url = 'http://{0}:9090'.format(prometheus_sip)
response = requests.get('{0}/api/v1/query_range'.format(prometheus_url),
        params={'query': sys.argv[3], 'start': sys.argv[1], 'end': sys.argv[2], 'step': '1'})
# print(response.text)
results = response.json()['data']['result']
label = sys.argv[4]

TIMESTAMP_INDEX = 0
VALUE_INDEX = 1

def search_metric(values, ts):
    l, r = 0, len(values) - 1
    while l <= r:
        m = (l + r) // 2
        if values[m][TIMESTAMP_INDEX] > ts:
            r = m - 1
        elif values[m][TIMESTAMP_INDEX] < ts:
            l = m + 1
        else:
            return values[m][VALUE_INDEX]
    return None


start = math.ceil(time.time())
end = 0
header = ['timestamp']
for result in results:
    loss = []
    start = min(start, result['values'][0][TIMESTAMP_INDEX])
    end = max(end, result['values'][-1][TIMESTAMP_INDEX])
    try:
        header.append(result['metric'][label])
    except KeyError:
        header.append(label)


writer = csv.writer(sys.stdout)
writer.writerow(header)

for ts in range(start, end + 1):
    row = [ts]
    for result in results:
        if ts >= result['values'][0][TIMESTAMP_INDEX] and ts <= result['values'][-1][TIMESTAMP_INDEX]:
            row.append(search_metric(result['values'], ts))
        else:
            row.append(None)
    writer.writerow(row)
