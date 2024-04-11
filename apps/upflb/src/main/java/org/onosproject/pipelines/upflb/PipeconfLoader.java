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

import com.google.common.collect.ImmutableList;
import org.onosproject.core.CoreService;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.device.PortStatisticsDiscovery;
import org.onosproject.net.pi.model.DefaultPiPipeconf;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.model.PiPipeconfId;
import org.onosproject.net.pi.model.PiPipelineInterpreter;
import org.onosproject.net.pi.model.PiPipelineModel;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.onosproject.p4runtime.model.P4InfoParser;
import org.onosproject.p4runtime.model.P4InfoParserException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

import java.net.URL;
import java.util.Collection;

import static org.onosproject.net.pi.model.PiPipeconf.ExtensionType.TOFINO_BIN;
import static org.onosproject.net.pi.model.PiPipeconf.ExtensionType.P4_INFO_TEXT;
import static org.onosproject.net.pi.model.PiPipeconf.ExtensionType.TOFINO_CONTEXT_JSON;

/**
 * Component that produces and registers the upflb pipeconfs when loaded.
 */
@Component(immediate = true)
public final class PipeconfLoader {

    private static final String APP_NAME = "org.onosproject.pipelines.upflb";
    private static final PiPipeconfId UPFLB_PIPECONF_ID = new PiPipeconfId("org.onosproject.pipelines.upflb");
    private static final String UPFLB_BIN_PATH = "/p4c-out/upflb/pipe/tofino.bin";
    private static final String UPFLB_P4INFO = "/p4c-out/upflb_p4info.txt";
    private static final String UPFLB_CONTEXT_JSON = "/p4c-out/upflb/pipe/context.json";

    public static final PiPipeconf UPFLB_PIPECONF = buildUpflbPipeconf();
    private static final Collection<PiPipeconf> ALL_PIPECONFS = ImmutableList.of(UPFLB_PIPECONF);

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private PiPipeconfService piPipeconfService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    private CoreService coreService;

    @Activate
    public void activate() {
        coreService.registerApplication(APP_NAME);
        // Registers all pipeconf at component activation.
        ALL_PIPECONFS.forEach(piPipeconfService::register);
    }

    @Deactivate
    public void deactivate() {
        ALL_PIPECONFS.stream().map(PiPipeconf::id).forEach(piPipeconfService::unregister);
    }

    private static PiPipeconf buildUpflbPipeconf() {
        final URL binUrl = PipeconfLoader.class.getResource(UPFLB_BIN_PATH);
        final URL p4InfoUrl = PipeconfLoader.class.getResource(UPFLB_P4INFO);
        final URL contextJsonUrl = PipeconfLoader.class.getResource(UPFLB_CONTEXT_JSON);

        return DefaultPiPipeconf.builder()
                .withId(UPFLB_PIPECONF_ID)
                .withPipelineModel(parseP4Info(p4InfoUrl))
                .addBehaviour(PiPipelineInterpreter.class, UpflbInterpreterImpl.class)
                .addExtension(P4_INFO_TEXT, p4InfoUrl)
                // Put here other target-specific extensions,
                // e.g. Tofino's bin and context.json.
                .addExtension(TOFINO_BIN, binUrl)
                .addExtension(TOFINO_CONTEXT_JSON, contextJsonUrl)
                .build();
    }

    private static PiPipelineModel parseP4Info(URL p4InfoUrl) {
        try {
            return P4InfoParser.parse(p4InfoUrl);
        } catch (P4InfoParserException e) {
            throw new IllegalStateException(e);
        }
    }
}
