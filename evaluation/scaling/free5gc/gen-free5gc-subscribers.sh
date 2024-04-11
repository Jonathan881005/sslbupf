#!/usr/bin/env bash

gen_post_data() {
	cat <<-EOT
	{
	  "plmnID": "20893",
	  "ueId": "${1}",
	  "AuthenticationSubscription": {
	    "authenticationManagementField": "8000",
	    "authenticationMethod": "5G_AKA",
	    "milenage": {
	      "op": {
	        "encryptionAlgorithm": 0,
	        "encryptionKey": 0,
	        "opValue": ""
	      }
	    },
	    "opc": {
	      "encryptionAlgorithm": 0,
	      "encryptionKey": 0,
	      "opcValue": "8e27b6af0e692e750f32667a3b14605d"
	    },
	    "permanentKey": {
	      "encryptionAlgorithm": 0,
	      "encryptionKey": 0,
	      "permanentKeyValue": "8baf473f2f8fd09487cccbd7097c6862"
	    },
	    "sequenceNumber": "16f3b3f70fc2"
	  },
	  "AccessAndMobilitySubscriptionData": {
	    "gpsis": [
	      "msisdn-0900000000"
	    ],
	    "nssai": {
	      "defaultSingleNssais": [
	        {
	          "sd": "010203",
	          "sst": 1
	        },
	        {
	          "sd": "112233",
	          "sst": 1
	        }
	      ],
	      "singleNssais": [
	        {
	          "sd": "010203",
	          "sst": 1
	        },
	        {
	          "sd": "112233",
	          "sst": 1
	        }
	      ]
	    },
	    "subscribedUeAmbr": {
	      "downlink": "2 Gbps",
	      "uplink": "1 Gbps"
	    }
	  },
	  "SessionManagementSubscriptionData": {
	    "singleNssai": {
	      "sst": 1,
	      "sd": "010203"
	    },
	    "dnnConfigurations": {
	      "internet": {
	        "sscModes": {
	          "defaultSscMode": "SSC_MODE_1",
	          "allowedSscModes": [
	            "SSC_MODE_1",
	            "SSC_MODE_2",
	            "SSC_MODE_3"
	          ]
	        },
	        "pduSessionTypes": {
	          "defaultSessionType": "IPV4",
	          "allowedSessionTypes": [
	            "IPV4"
	          ]
	        },
	        "sessionAmbr": {
	          "uplink": "2 Gbps",
	          "downlink": "1 Gbps"
	        },
	        "5gQosProfile": {
	          "5qi": 9,
	          "arp": {
	            "priorityLevel": 8
	          },
	          "priorityLevel": 8
	        }
	      }
	    }
	  },
	  "SmfSelectionSubscriptionData": {
	    "subscribedSnssaiInfos": {
	      "01010203": {
	        "dnnInfos": [
	          {
	            "dnn": "internet"
	          }
	        ]
	      },
	      "01112233": {
	        "dnnInfos": [
	          {
	            "dnn": "internet"
	          }
	        ]
	      }
	    }
	  },
	  "AmPolicyData": {
	    "subscCats": [
	      "free5gc"
	    ]
	  },
	  "SmPolicyData": {
	    "smPolicySnssaiData": {
	      "01010203": {
	        "snssai": {
	          "sst": 1,
	          "sd": "010203"
	        },
	        "smPolicyDnnData": {
	          "internet": {
	            "dnn": "internet"
	          }
	        }
	      },
	      "01112233": {
	        "snssai": {
	          "sst": 1,
	          "sd": "112233"
	        },
	        "smPolicyDnnData": {
	          "internet": {
	            "dnn": "internet"
	          }
	        }
	      }
	    }
	  }
	}
	EOT
}

for i in {1..20}; do
	IMSI="imsi-$((208930000000000+${i}))"
	curl -X POST "http://localhost:30300/api/subscriber/${IMSI}/20893" -H "Content-Type: application/json" -d "$(gen_post_data ${IMSI})"
done
