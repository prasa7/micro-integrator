/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.wso2.micro.integrator.management.apis;

import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.config.xml.inbound.InboundEndpointSerializer;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundEndpoint;
import org.json.JSONArray;
import org.json.JSONObject;
import org.wso2.carbon.inbound.endpoint.internal.http.api.APIResource;

import java.io.IOException;

import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.wso2.micro.integrator.management.apis.Constants.SEARCH_KEY;
import static org.wso2.micro.integrator.management.apis.Constants.SYNAPSE_CONFIGURATION;

public class InboundEndpointResource extends APIResource {

    private static Log LOG = LogFactory.getLog(InboundEndpointResource.class);

    private static final String INBOUND_ENDPOINT_NAME = "inboundEndpointName";

    public InboundEndpointResource(String urlTemplate){
        super(urlTemplate);
    }

    @Override
    public Set<String> getMethods() {
        Set<String> methods = new HashSet<>();
        methods.add(Constants.HTTP_GET);
        methods.add(Constants.HTTP_POST);
        return methods;
    }

    @Override
    public boolean invoke(MessageContext messageContext) {

        buildMessage(messageContext);
        org.apache.axis2.context.MessageContext axisMsgCtx =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        String inboundName = Utils.getQueryParameter(messageContext, INBOUND_ENDPOINT_NAME);
        String searchKey = Utils.getQueryParameter(messageContext, SEARCH_KEY);

        if (messageContext.isDoingGET()) {
            if (Objects.nonNull(inboundName)) {
                populateInboundEndpointData(messageContext, inboundName);
            } else if (Objects.nonNull(searchKey) && !searchKey.trim().isEmpty()) {
                populateSearchResults(messageContext, searchKey.toLowerCase());
            } else {
                populateInboundEndpointList(messageContext);
            }
        } else {
            handlePost(messageContext, axisMsgCtx);
        }
        return true;
    }

    private static List<InboundEndpoint> getSearchResults (MessageContext messageContext, String searchKey) {

        SynapseConfiguration configuration = messageContext.getConfiguration();
        List<InboundEndpoint> searchResultList = configuration.getInboundEndpoints().stream()
                .filter(artifact -> artifact.getName().toLowerCase().contains(searchKey))
                .collect(Collectors.toList());
        return searchResultList;
    }

    private void populateSearchResults(MessageContext messageContext, String searchKey) {

        List<InboundEndpoint> resultsList = getSearchResults(messageContext, searchKey);
        setResponseBody(resultsList, messageContext);
    }

    private void handlePost(MessageContext msgCtx,
                            org.apache.axis2.context.MessageContext axisMsgCtx) {

        JSONObject response;
        try {
            JsonObject payload = Utils.getJsonPayload(axisMsgCtx);
            if (payload.has(Constants.NAME)) {
                String inboundName = payload.get(Constants.NAME).getAsString();
                SynapseConfiguration configuration = msgCtx.getConfiguration();
                InboundEndpoint inboundEndpoint = configuration.getInboundEndpoint(inboundName);
                if (inboundEndpoint != null) {
                    String performedBy = Constants.ANONYMOUS_USER;
                    if (msgCtx.getProperty(Constants.USERNAME_PROPERTY) !=  null) {
                        performedBy = msgCtx.getProperty(Constants.USERNAME_PROPERTY).toString();
                    }
                    JSONObject info = new JSONObject();
                    info.put(INBOUND_ENDPOINT_NAME, inboundName);
                    response = Utils.handleTracing(performedBy, Constants.AUDIT_LOG_TYPE_INBOUND_ENDPOINT_TRACE,
                                                   Constants.INBOUND_ENDPOINTS, info,
                                                   inboundEndpoint.getAspectConfiguration(), inboundName, axisMsgCtx);
                } else {
                    response = Utils.createJsonError("Specified inbound endpoint ('" + inboundName + "') not found",
                            axisMsgCtx, Constants.BAD_REQUEST);
                }
            } else {
                response = Utils.createJsonError("Unsupported operation", axisMsgCtx, Constants.BAD_REQUEST);
            }
            Utils.setJsonPayLoad(axisMsgCtx, response);
        } catch (IOException e) {
            LOG.error("Error when parsing JSON payload", e);
            Utils.setJsonPayLoad(axisMsgCtx, Utils.createJsonErrorObject("Error when parsing JSON payload"));
        }
    }

    private void populateInboundEndpointList(MessageContext messageContext) {

        SynapseConfiguration configuration = messageContext.getConfiguration();
        Collection<InboundEndpoint> inboundEndpoints = configuration.getInboundEndpoints();
        setResponseBody(inboundEndpoints, messageContext);
    }
    private void setResponseBody(Collection<InboundEndpoint> inboundEndpointCollection, MessageContext messageContext) {

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        JSONObject jsonBody = Utils.createJSONList(inboundEndpointCollection.size());

        for (InboundEndpoint inboundEndpoint : inboundEndpointCollection) {
            JSONObject inboundObject = new JSONObject();

            inboundObject.put(Constants.NAME, inboundEndpoint.getName());
            inboundObject.put("protocol", inboundEndpoint.getProtocol());

            jsonBody.getJSONArray(Constants.LIST).put(inboundObject);
        }
        Utils.setJsonPayLoad(axis2MessageContext, jsonBody);
    }

    private void populateInboundEndpointData(MessageContext messageContext, String inboundEndpointName) {

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        JSONObject jsonBody = getInboundEndpointByName(messageContext, inboundEndpointName);

        if (Objects.nonNull(jsonBody)) {
            Utils.setJsonPayLoad(axis2MessageContext, jsonBody);
        } else {
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.NOT_FOUND);
        }
    }

    private JSONObject getInboundEndpointByName(MessageContext messageContext, String inboundEndpointName) {

        SynapseConfiguration configuration = messageContext.getConfiguration();
        InboundEndpoint ep = configuration.getInboundEndpoint(inboundEndpointName);
        return convertInboundEndpointToJsonObject(ep);
    }

    private JSONObject convertInboundEndpointToJsonObject(InboundEndpoint inboundEndpoint) {

        if (Objects.isNull(inboundEndpoint)) {
            return null;
        }

        JSONObject inboundObject = new JSONObject();

        inboundObject.put(Constants.NAME, inboundEndpoint.getName());
        inboundObject.put("protocol", inboundEndpoint.getProtocol());
        inboundObject.put("sequence", inboundEndpoint.getInjectingSeq());
        inboundObject.put("error", inboundEndpoint.getOnErrorSeq());

        String statisticState = inboundEndpoint.getAspectConfiguration().isStatisticsEnable() ? Constants.ENABLED : Constants.DISABLED;
        inboundObject.put(Constants.STATS, statisticState);

        String tracingState = inboundEndpoint.getAspectConfiguration().isTracingEnabled() ? Constants.ENABLED : Constants.DISABLED;
        inboundObject.put(Constants.TRACING, tracingState);
        inboundObject.put(SYNAPSE_CONFIGURATION, InboundEndpointSerializer.serializeInboundEndpoint(inboundEndpoint));

        JSONArray parameterListObject = new JSONArray();

        inboundObject.put("parameters", parameterListObject);

        Map<String, String> params = inboundEndpoint.getParametersMap();

        for (Map.Entry<String,String> param : params.entrySet()) {

            JSONObject paramObject = new JSONObject();

            paramObject.put(Constants.NAME, param.getKey());
            paramObject.put("value", param.getValue());

            parameterListObject.put(paramObject);
        }
        return inboundObject;
    }
}
