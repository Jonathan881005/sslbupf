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

package org.onosproject.pipelines.upflb;

import org.onosproject.net.pi.model.PiActionId;
import org.onosproject.net.pi.model.PiActionParamId;
import org.onosproject.net.pi.model.PiActionProfileId;
import org.onosproject.net.pi.model.PiMeterId;
import org.onosproject.net.pi.model.PiPacketMetadataId;
import org.onosproject.net.pi.model.PiCounterId;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.pi.model.PiTableId;
/**
 * Constants for upflb pipeline.
 */
public final class UpflbConstants {

    // hide default constructor
    private UpflbConstants() {
    }

    // Header field IDs
    public static final PiMatchFieldId HDR_HDR_IPV4_SRC_ADDR =
            PiMatchFieldId.of("hdr.ipv4.src_addr");
    public static final PiMatchFieldId HDR_HDR_IPV4_DST_ADDR =
            PiMatchFieldId.of("hdr.ipv4.dst_addr");
    public static final PiMatchFieldId HDR_HDR_GTPU_TEID =
            PiMatchFieldId.of("hdr.gtpu.teid");
    // Table IDs
    public static final PiTableId INGRESS_UPF_SNAT_TABLE =
            PiTableId.of("Ingress.upf_snat_table");
    public static final PiTableId INGRESS_UPF_DNAT_TABLE =
            PiTableId.of("Ingress.upf_dnat_table");
    public static final PiTableId INGRESS_IP_ROUTE_TABLE =
            PiTableId.of("Ingress.ip_route_table");
    // Direct Counter IDs
    public static final PiCounterId INGRESS_UPF_DNAT_COUNTER =
            PiCounterId.of("Ingress.upf_dnat_counter");
    public static final PiCounterId INGRESS_IP_ROUTE_COUNTER =
            PiCounterId.of("Ingress.ip_route_counter");
    public static final PiCounterId INGRESS_UPF_SNAT_COUNTER =
            PiCounterId.of("Ingress.upf_snat_counter");
    // Action IDs
    public static final PiActionId INGRESS_SEND_TO_CPU =
            PiActionId.of("Ingress.send_to_cpu");
    public static final PiActionId INGRESS_UPF_SNAT =
            PiActionId.of("Ingress.upf_snat");
    public static final PiActionId INGRESS_SEND = PiActionId.of("Ingress.send");
    public static final PiActionId INGRESS_UPF_DNAT =
            PiActionId.of("Ingress.upf_dnat");
    public static final PiActionId NO_ACTION = PiActionId.of("NoAction");
    // Action Param IDs
    public static final PiActionParamId UPF_VIP = PiActionParamId.of("upf_vip");
    public static final PiActionParamId SMAC = PiActionParamId.of("smac");
    public static final PiActionParamId PORT = PiActionParamId.of("port");
    public static final PiActionParamId UPF_DIP = PiActionParamId.of("upf_dip");
    public static final PiActionParamId DMAC = PiActionParamId.of("dmac");
    // Packet Metadata IDs
    public static final PiPacketMetadataId INGRESS_PORT =
            PiPacketMetadataId.of("ingress_port");
    public static final PiPacketMetadataId EGRESS_PORT =
            PiPacketMetadataId.of("egress_port");
}