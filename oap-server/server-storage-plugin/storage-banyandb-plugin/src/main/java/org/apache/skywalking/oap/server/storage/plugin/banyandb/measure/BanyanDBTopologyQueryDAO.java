/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.banyandb.measure;

import com.google.common.collect.ImmutableSet;
import org.apache.skywalking.banyandb.v1.client.MeasureQuery;
import org.apache.skywalking.banyandb.v1.client.MeasureQueryResponse;
import org.apache.skywalking.banyandb.v1.client.TimestampRange;
import org.apache.skywalking.oap.server.core.UnexpectedException;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.analysis.manual.relation.endpoint.EndpointRelationServerSideMetrics;
import org.apache.skywalking.oap.server.core.analysis.manual.relation.instance.ServiceInstanceRelationServerSideMetrics;
import org.apache.skywalking.oap.server.core.analysis.manual.relation.service.ServiceRelationClientSideMetrics;
import org.apache.skywalking.oap.server.core.analysis.manual.relation.service.ServiceRelationServerSideMetrics;
import org.apache.skywalking.oap.server.core.query.type.Call;
import org.apache.skywalking.oap.server.core.source.DetectPoint;
import org.apache.skywalking.oap.server.core.storage.query.ITopologyQueryDAO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageClient;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.AbstractBanyanDBDAO;

public class BanyanDBTopologyQueryDAO extends AbstractBanyanDBDAO implements ITopologyQueryDAO {

    public BanyanDBTopologyQueryDAO(final BanyanDBStorageClient client) {
        super(client);
    }

    @Override
    public List<Call.CallDetail> loadServiceRelationsDetectedAtServerSide(long startTB, long endTB, List<String> serviceIds) throws IOException {
        if (CollectionUtils.isEmpty(serviceIds)) {
            throw new UnexpectedException("Service id is empty");
        }

        List<QueryBuilder<MeasureQuery>> queryBuilderList = buildServiceRelationsQueries(serviceIds);

        return queryServiceRelation(startTB, endTB, queryBuilderList, DetectPoint.SERVER);
    }

    @Override
    public List<Call.CallDetail> loadServiceRelationDetectedAtClientSide(long startTB, long endTB, List<String> serviceIds) throws IOException {
        if (CollectionUtils.isEmpty(serviceIds)) {
            throw new UnexpectedException("Service id is empty");
        }

        List<QueryBuilder<MeasureQuery>> queryBuilderList = buildServiceRelationsQueries(serviceIds);

        return queryServiceRelation(startTB, endTB, queryBuilderList, DetectPoint.CLIENT);
    }

    @Override
    public List<Call.CallDetail> loadServiceRelationsDetectedAtServerSide(long startTB, long endTB) throws IOException {
        return queryServiceRelation(startTB, endTB, Collections.singletonList(emptyMeasureQuery()), DetectPoint.SERVER);
    }

    @Override
    public List<Call.CallDetail> loadServiceRelationDetectedAtClientSide(long startTB, long endTB) throws IOException {
        return queryServiceRelation(startTB, endTB, Collections.singletonList(emptyMeasureQuery()), DetectPoint.CLIENT);
    }

    private List<QueryBuilder<MeasureQuery>> buildServiceRelationsQueries(List<String> serviceIds) {
        List<QueryBuilder<MeasureQuery>> queryBuilderList = new ArrayList<>(serviceIds.size());
        for (final String serviceId : serviceIds) {
            queryBuilderList.add(new QueryBuilder<MeasureQuery>() {
                @Override
                protected void apply(MeasureQuery query) {
                    query.and(eq(ServiceRelationServerSideMetrics.SOURCE_SERVICE_ID, serviceId));
                }
            });

            queryBuilderList.add(new QueryBuilder<MeasureQuery>() {
                @Override
                protected void apply(MeasureQuery query) {
                    query.and(eq(ServiceRelationServerSideMetrics.DEST_SERVICE_ID, serviceId));
                }
            });
        }
        return queryBuilderList;
    }

    List<Call.CallDetail> queryServiceRelation(long startTB, long endTB, List<QueryBuilder<MeasureQuery>> queryBuilderList, DetectPoint detectPoint) throws IOException {
        TimestampRange timestampRange = null;
        if (startTB > 0 && endTB > 0) {
            timestampRange = new TimestampRange(TimeBucket.getTimestamp(startTB), TimeBucket.getTimestamp(endTB));
        }
        final Map<String, Call.CallDetail> callMap = new HashMap<>();
        for (final QueryBuilder<MeasureQuery> q : queryBuilderList) {
            MeasureQueryResponse resp = query(ServiceRelationClientSideMetrics.INDEX_NAME,
                    ImmutableSet.of(ServiceRelationClientSideMetrics.COMPONENT_ID,
                            ServiceRelationClientSideMetrics.SOURCE_SERVICE_ID,
                            ServiceRelationClientSideMetrics.DEST_SERVICE_ID,
                            ServiceRelationClientSideMetrics.ENTITY_ID),
                    Collections.emptySet(), timestampRange, q);
            if (resp.size() == 0) {
                continue;
            }
            final Call.CallDetail call = new Call.CallDetail();
            final String entityId = resp.getDataPoints().get(0).getTagValue(ServiceRelationClientSideMetrics.ENTITY_ID);
            final int componentId = ((Number) resp.getDataPoints().get(0).getTagValue(ServiceRelationClientSideMetrics.COMPONENT_ID)).intValue();
            call.buildFromServiceRelation(entityId, componentId, detectPoint);
            callMap.putIfAbsent(entityId, call);
        }
        return new ArrayList<>(callMap.values());
    }

    @Override
    public List<Call.CallDetail> loadInstanceRelationDetectedAtServerSide(String clientServiceId, String serverServiceId, long startTB, long endTB) throws IOException {
        List<QueryBuilder<MeasureQuery>> queryBuilderList = buildInstanceRelationsQueries(clientServiceId, serverServiceId);
        return queryInstanceRelation(startTB, endTB, queryBuilderList, DetectPoint.SERVER);
    }

    @Override
    public List<Call.CallDetail> loadInstanceRelationDetectedAtClientSide(String clientServiceId, String serverServiceId, long startTB, long endTB) throws IOException {
        List<QueryBuilder<MeasureQuery>> queryBuilderList = buildInstanceRelationsQueries(clientServiceId, serverServiceId);
        return queryInstanceRelation(startTB, endTB, queryBuilderList, DetectPoint.CLIENT);
    }

    private List<QueryBuilder<MeasureQuery>> buildInstanceRelationsQueries(String clientServiceId, String serverServiceId) {
        List<QueryBuilder<MeasureQuery>> queryBuilderList = new ArrayList<>(2);
        queryBuilderList.add(new QueryBuilder<MeasureQuery>() {
            @Override
            protected void apply(MeasureQuery query) {
                query.and(eq(ServiceInstanceRelationServerSideMetrics.SOURCE_SERVICE_ID, clientServiceId))
                        .and(eq(ServiceInstanceRelationServerSideMetrics.DEST_SERVICE_ID, serverServiceId));
            }
        });

        queryBuilderList.add(new QueryBuilder<MeasureQuery>() {
            @Override
            protected void apply(MeasureQuery query) {
                query.and(eq(ServiceInstanceRelationServerSideMetrics.DEST_SERVICE_ID, clientServiceId))
                        .and(eq(ServiceInstanceRelationServerSideMetrics.SOURCE_SERVICE_ID, serverServiceId));
            }
        });
        return queryBuilderList;
    }

    List<Call.CallDetail> queryInstanceRelation(long startTB, long endTB, List<QueryBuilder<MeasureQuery>> queryBuilderList, DetectPoint detectPoint) throws IOException {
        TimestampRange timestampRange = null;
        if (startTB > 0 && endTB > 0) {
            timestampRange = new TimestampRange(TimeBucket.getTimestamp(startTB), TimeBucket.getTimestamp(endTB));
        }
        final Map<String, Call.CallDetail> callMap = new HashMap<>();
        for (final QueryBuilder<MeasureQuery> q : queryBuilderList) {
            MeasureQueryResponse resp = query(ServiceInstanceRelationServerSideMetrics.INDEX_NAME,
                    ImmutableSet.of(ServiceInstanceRelationServerSideMetrics.COMPONENT_ID,
                            ServiceInstanceRelationServerSideMetrics.SOURCE_SERVICE_ID,
                            ServiceInstanceRelationServerSideMetrics.DEST_SERVICE_ID,
                            ServiceInstanceRelationServerSideMetrics.ENTITY_ID),
                    Collections.emptySet(), timestampRange, q);
            if (resp.size() == 0) {
                continue;
            }
            final Call.CallDetail call = new Call.CallDetail();
            final String entityId = resp.getDataPoints().get(0).getTagValue(ServiceInstanceRelationServerSideMetrics.ENTITY_ID);
            final int componentId = ((Number) resp.getDataPoints().get(0).getTagValue(ServiceRelationClientSideMetrics.COMPONENT_ID)).intValue();
            call.buildFromInstanceRelation(entityId, componentId, detectPoint);
            callMap.putIfAbsent(entityId, call);
        }
        return new ArrayList<>(callMap.values());
    }

    @Override
    public List<Call.CallDetail> loadEndpointRelation(long startTB, long endTB, String destEndpointId) throws IOException {
        List<QueryBuilder<MeasureQuery>> queryBuilderList = buildEndpointRelationsQueries(destEndpointId);
        return queryEndpointRelation(startTB, endTB, queryBuilderList, DetectPoint.SERVER);
    }

    private List<QueryBuilder<MeasureQuery>> buildEndpointRelationsQueries(String destEndpointId) {
        List<QueryBuilder<MeasureQuery>> queryBuilderList = new ArrayList<>(2);
        queryBuilderList.add(new QueryBuilder<MeasureQuery>() {
            @Override
            protected void apply(MeasureQuery query) {
                query.and(eq(EndpointRelationServerSideMetrics.SOURCE_ENDPOINT, destEndpointId));
            }
        });

        queryBuilderList.add(new QueryBuilder<MeasureQuery>() {
            @Override
            protected void apply(MeasureQuery query) {
                query.and(eq(EndpointRelationServerSideMetrics.DEST_ENDPOINT, destEndpointId));
            }
        });
        return queryBuilderList;
    }

    List<Call.CallDetail> queryEndpointRelation(long startTB, long endTB, List<QueryBuilder<MeasureQuery>> queryBuilderList, DetectPoint detectPoint) throws IOException {
        TimestampRange timestampRange = null;
        if (startTB > 0 && endTB > 0) {
            timestampRange = new TimestampRange(TimeBucket.getTimestamp(startTB), TimeBucket.getTimestamp(endTB));
        }
        final Map<String, Call.CallDetail> callMap = new HashMap<>();
        for (final QueryBuilder<MeasureQuery> q : queryBuilderList) {
            MeasureQueryResponse resp = query(EndpointRelationServerSideMetrics.INDEX_NAME,
                    ImmutableSet.of(EndpointRelationServerSideMetrics.DEST_ENDPOINT,
                            EndpointRelationServerSideMetrics.SOURCE_ENDPOINT,
                            EndpointRelationServerSideMetrics.ENTITY_ID),
                    Collections.emptySet(), timestampRange, q);
            if (resp.size() == 0) {
                continue;
            }
            final Call.CallDetail call = new Call.CallDetail();
            final String entityId = resp.getDataPoints().get(0).getTagValue(EndpointRelationServerSideMetrics.ENTITY_ID);
            call.buildFromEndpointRelation(entityId, detectPoint);
            callMap.putIfAbsent(entityId, call);
        }
        return new ArrayList<>(callMap.values());
    }
}
