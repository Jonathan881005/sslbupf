#!/usr/bin/env bash

# Run this script inside the mongo container.

if [ ! -f "./open5gs-dbctl" ]; then
    wget https://raw.githubusercontent.com/COCUSAG/open5gs/87575524620dfed19e3036c951d8c27b96cbaeef/misc/db/open5gs-dbctl
fi

for i in {01..20}; do
    bash open5gs-dbctl add 2089300000000${i} "465B5CE8 B199B49F AA5F0A2E E238A6BC" "E8ED289D EBA952E4 283B54E8 8E6183CA"
done

# gen_post_data() {
# 	cat <<-EOT
# 	{
#       "imsi": "${1}",
#       "security": {
#         "k": "465B5CE8 B199B49F AA5F0A2E E238A6BC",
#         "amf": "8000",
#         "op_type": 0,
#         "op_value": "E8ED289D EBA952E4 283B54E8 8E6183CA",
#         "op": null,
#         "opc": "E8ED289D EBA952E4 283B54E8 8E6183CA"
#       },
#       "ambr": {
#         "downlink": {
#           "value": 1,
#           "unit": 3
#         },
#         "uplink": {
#           "value": 1,
#           "unit": 3
#         }
#       },
#       "slice": [
#         {
#           "sst": 1,
#           "default_indicator": true,
#           "session": [
#             {
#               "name": "internet",
#               "type": 3,
#               "ambr": {
#                 "downlink": {
#                   "value": 1,
#                   "unit": 3
#                 },
#                 "uplink": {
#                   "value": 1,
#                   "unit": 3
#                 }
#               },
#               "qos": {
#                 "index": 9,
#                 "arp": {
#                   "priority_level": 8,
#                   "pre_emption_capability": 1,
#                   "pre_emption_vulnerability": 1
#                 }
#               }
#             }
#           ]
#         }
#       ]
#     }
# 	EOT
# }

# for i in {1..20}; do
# 	IMSI="$((208930000000000+${i}))"
# 	curl -X POST "http://localhost:30300/api/db/Subscriber" -H "Content-Type: application/json" -d "$(gen_post_data ${IMSI})"
# done
