/* -*- P4_16 -*- */

#include <core.p4>
#include <tna.p4>

#include "include/headers.p4"
#include "include/defines.p4"
#include "include/parsers.p4"

//------------------------------------------------------------------------------
// INGRESS PIPELINE
//------------------------------------------------------------------------------

control Ingress(
    /* User */
    inout ingress_headers_t hdr,
    inout ingress_metadata_t meta,
    /* Intrinsic */
    in ingress_intrinsic_metadata_t ig_intr_md,
    in ingress_intrinsic_metadata_from_parser_t ig_prsr_md,
    inout ingress_intrinsic_metadata_for_deparser_t ig_dprsr_md,
    inout ingress_intrinsic_metadata_for_tm_t ig_tm_md) {

    DirectCounter<bit<64>>(CounterType_t.PACKETS_AND_BYTES) upf_dnat_counter;
    DirectCounter<bit<64>>(CounterType_t.PACKETS_AND_BYTES) upf_snat_counter;
    DirectCounter<bit<64>>(CounterType_t.PACKETS_AND_BYTES) ip_route_counter;

    action upf_dnat(ipv4_addr_t upf_dip) {
        hdr.ipv4.dst_addr = upf_dip;
        hdr.udp.checksum = 0;
        upf_dnat_counter.count();
    }

    action upf_snat(ipv4_addr_t upf_vip) {
        hdr.ipv4.src_addr = upf_vip;
        hdr.udp.checksum = 0;
        upf_snat_counter.count();
    }

    action send(PortId_t port, mac_t smac, mac_t dmac) {
        ig_tm_md.ucast_egress_port = port;
        hdr.ethernet.src_addr = smac;
        hdr.ethernet.dst_addr = dmac;
        ip_route_counter.count();
    }

    action send_to_cpu() {
        ig_tm_md.ucast_egress_port = CPU_PORT;
        hdr.packet_in.setValid();
        hdr.packet_in.ingress_port = ig_intr_md.ingress_port;
        hdr.packet_in._padding = 0;
    }

    // Use ternary match to enable priority.
    // Ref: https://p4.org/p4-spec/p4runtime/v1.0.0/P4Runtime-Spec.html#sec-table-entry
    table upf_dnat_table {
        key = {
            hdr.ipv4.dst_addr: ternary;
            hdr.gtpu.teid: ternary;
        }
        actions = {
            upf_dnat;
            @defaultonly NoAction;
        }
        // TODO: Change default action to packet-in.
        default_action = NoAction;
        // TODO: Use constant or macro here.
        size = 512;
        counters = upf_dnat_counter;
    }

    table upf_snat_table {
        key = {
            hdr.ipv4.src_addr: exact;
        }
        actions = {
            upf_snat;
            @defaultonly NoAction;
        }
        default_action = NoAction;
        size = 512;
        counters = upf_snat_counter;
    }

    // Use ternary match to enable priority.
    // Ref: https://p4.org/p4-spec/p4runtime/v1.0.0/P4Runtime-Spec.html#sec-table-entry
    table ip_route_table {
        key = {
            hdr.ipv4.dst_addr: ternary;
        }
        actions = {
            send;
            @defaultonly NoAction;
        }
        default_action = NoAction;
        size = 512;
        counters = ip_route_counter;
    }

    apply {
        if (ig_intr_md.ingress_port == CPU_PORT) {
            ig_tm_md.ucast_egress_port = hdr.packet_out.egress_port;
            hdr.packet_out.setInvalid();
            ig_tm_md.bypass_egress = 1;
            exit; // Early return
        }

        if (hdr.ethernet.ether_type == ETH_TYPE_ARP) {
            send_to_cpu();
        } else if (hdr.ethernet.ether_type == ETH_TYPE_IPV4) {
            if (hdr.ipv4.protocol == IP_PROTO_UDP && hdr.udp.dst_port == UDP_PORT_GTPU) {
                upf_dnat_table.apply();
                upf_snat_table.apply();
            }
            ip_route_table.apply();
        }
    }
}

//------------------------------------------------------------------------------
// EGRESS PIPELINE
//------------------------------------------------------------------------------

control Egress(
    /* User */
    inout egress_headers_t hdr,
    inout egress_metadata_t meta,
    /* Intrinsic */
    in egress_intrinsic_metadata_t eg_intr_md,
    in egress_intrinsic_metadata_from_parser_t eg_prsr_md,
    inout egress_intrinsic_metadata_for_deparser_t eg_dprsr_md,
    inout egress_intrinsic_metadata_for_output_port_t eg_oport_md) {

    apply {
    }
}

//------------------------------------------------------------------------------
// SWITCH INSTANTIATION
//------------------------------------------------------------------------------

Pipeline(IngressParser(),
         Ingress(),
         IngressDeparser(),
         EgressParser(),
         Egress(),
         EgressDeparser()) pipe;

Switch(pipe) main;
