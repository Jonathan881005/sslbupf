/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef __DEFINES__
#define __DEFINES__

#define CPU_PORT 192
#define ETH_TYPE_IPV4 16w0x0800
#define ETH_TYPE_ARP 16w0x0806
#define IP_PROTO_TCP 8w6
#define IP_PROTO_UDP 8w17
#define IP_PROTO_SCTP 8w132
#define UDP_PORT_PFCP 16w8805
#define UDP_PORT_GTPU 16w2152

typedef bit<48> mac_t;
typedef bit<32> ipv4_addr_t;
typedef bit<16> l4_port_t;
typedef bit<9>  port_t;
typedef bit<16> next_hop_id_t;
typedef bit<32> teid_t;

#endif
