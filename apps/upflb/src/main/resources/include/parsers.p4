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

#ifndef __PARSERS__
#define __PARSERS__

#include "headers.p4"
#include "defines.p4"

//------------------------------------------------------------------------------
// INGRESS (DE)PARSER
//------------------------------------------------------------------------------

// TODO: If I need to process control plane packets, be sure to add corresponding headers here
struct ingress_headers_t {
    packet_in_header_t packet_in;
    packet_out_header_t packet_out;
    ethernet_h ethernet;
    ipv4_h ipv4;
    udp_h udp;
    gtpu_h gtpu;
}

struct ingress_metadata_t {
    port_t ingress_port;
}

parser IngressParser(
    packet_in pkt,
    out ingress_headers_t hdr,
    out ingress_metadata_t meta,
    out ingress_intrinsic_metadata_t ig_intr_md) {

    /* This is a mandatory state, required by Tofino Architecture */
    state start {
        pkt.extract(ig_intr_md);
        // Why should we advance here? What does advance() mean?
        pkt.advance(PORT_METADATA_SIZE);
        meta.ingress_port = ig_intr_md.ingress_port; //TODO: Maybe this line can be removed?
        transition select(ig_intr_md.ingress_port) {
            CPU_PORT: parse_packet_out;
            default: parse_ethernet;
        }
    }

    state parse_packet_out {
        pkt.extract(hdr.packet_out);
        transition parse_ethernet;
    }

    state parse_ethernet {
        pkt.extract(hdr.ethernet);
        transition select(hdr.ethernet.ether_type) {
            ETH_TYPE_IPV4: parse_ipv4;
            default: accept;
        }
    }

    state parse_ipv4 {
        pkt.extract(hdr.ipv4);
        transition select(hdr.ipv4.protocol) {
            IP_PROTO_UDP: parse_udp;
            default: accept;
        }
    }

    state parse_udp {
        pkt.extract(hdr.udp);
        transition select(hdr.udp.dst_port) {
            UDP_PORT_GTPU: parse_gtpu;
            default: accept;
        }
    }

    state parse_gtpu {
        pkt.extract(hdr.gtpu);
        transition accept;
    }
}

control IngressDeparser(
    packet_out pkt,
    inout ingress_headers_t hdr,
    in ingress_metadata_t meta,
    in ingress_intrinsic_metadata_for_deparser_t ig_dprsr_md) {

    Checksum() ipv4_checksum;

    apply {
        hdr.ipv4.hdr_checksum = ipv4_checksum.update(
                {hdr.ipv4.version,
                hdr.ipv4.ihl,
                hdr.ipv4.dscp,
                hdr.ipv4.ecn,
                hdr.ipv4.len,
                hdr.ipv4.identification,
                hdr.ipv4.flags,
                hdr.ipv4.frag_offset,
                hdr.ipv4.ttl,
                hdr.ipv4.protocol,
                hdr.ipv4.src_addr,
                hdr.ipv4.dst_addr});

        //TODO: Update UDP checksum

        pkt.emit(hdr);
    }
}

//------------------------------------------------------------------------------
// EGRESS (DE)PARSER
//------------------------------------------------------------------------------

struct egress_headers_t {
}

struct egress_metadata_t {
}

parser EgressParser(
    packet_in pkt,
    /* User */
    out egress_headers_t hdr,
    out egress_metadata_t meta,
    /* Intrinsic */
    out egress_intrinsic_metadata_t eg_intr_md) {

    /* This is a mandatory state, required by Tofino Architecture */
    state start {
        pkt.extract(eg_intr_md);
        transition accept;
    }
}

control EgressDeparser(
    packet_out pkt,
    /* User */
    inout egress_headers_t hdr,
    in egress_metadata_t meta,
    /* Intrinsic */
    in egress_intrinsic_metadata_for_deparser_t eg_dprsr_md) {

    apply {
        pkt.emit(hdr);
    }
}

#endif
