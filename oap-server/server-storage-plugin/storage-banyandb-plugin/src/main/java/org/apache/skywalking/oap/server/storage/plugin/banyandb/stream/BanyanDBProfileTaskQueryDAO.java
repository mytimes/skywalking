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

package org.apache.skywalking.oap.server.storage.plugin.banyandb.stream;

import com.google.common.collect.ImmutableSet;
import org.apache.skywalking.banyandb.v1.client.RowEntity;
import org.apache.skywalking.banyandb.v1.client.StreamQuery;
import org.apache.skywalking.banyandb.v1.client.StreamQueryResponse;
import org.apache.skywalking.oap.server.core.profiling.trace.ProfileTaskRecord;
import org.apache.skywalking.oap.server.core.query.type.ProfileTask;
import org.apache.skywalking.oap.server.core.storage.profiling.trace.IProfileTaskQueryDAO;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class BanyanDBProfileTaskQueryDAO extends AbstractBanyanDBDAO implements IProfileTaskQueryDAO {
    private static final Set<String> TAGS = ImmutableSet.of(
            ProfileTaskRecord.SERVICE_ID,
            ProfileTaskRecord.ENDPOINT_NAME,
            ProfileTaskRecord.START_TIME,
            ProfileTaskRecord.CREATE_TIME,
            ProfileTaskRecord.DURATION,
            ProfileTaskRecord.MIN_DURATION_THRESHOLD,
            ProfileTaskRecord.DUMP_PERIOD,
            ProfileTaskRecord.MAX_SAMPLING_COUNT
    );

    public BanyanDBProfileTaskQueryDAO(BanyanDBStorageClient client) {
        super(client);
    }

    @Override
    public List<ProfileTask> getTaskList(String serviceId, String endpointName, Long startTimeBucket, Long endTimeBucket, Integer limit) throws IOException {
        StreamQueryResponse resp = query(ProfileTaskRecord.INDEX_NAME, TAGS,
                new QueryBuilder<StreamQuery>() {
                    @Override
                    protected void apply(StreamQuery query) {
                        if (StringUtil.isNotEmpty(serviceId)) {
                            query.and(eq(ProfileTaskRecord.SERVICE_ID, serviceId));
                        }
                        if (StringUtil.isNotEmpty(endpointName)) {
                            query.and(eq(ProfileTaskRecord.ENDPOINT_NAME, endpointName));
                        }
                        if (startTimeBucket != null) {
                            query.and(gte(ProfileTaskRecord.TIME_BUCKET, startTimeBucket));
                        }
                        if (endTimeBucket != null) {
                            query.and(lte(ProfileTaskRecord.TIME_BUCKET, endTimeBucket));
                        }
                        if (limit != null) {
                            query.setLimit(limit);
                        }
                    }
                });

        if (resp.size() == 0) {
            return Collections.emptyList();
        }

        List<ProfileTask> profileTasks = new ArrayList<>(resp.size());
        for (final RowEntity entity : resp.getElements()) {
            profileTasks.add(buildProfileTask(entity));
        }

        return profileTasks;
    }

    @Override
    public ProfileTask getById(String id) throws IOException {
        StreamQueryResponse resp = query(ProfileTaskRecord.INDEX_NAME, TAGS,
                new QueryBuilder<StreamQuery>() {
                    @Override
                    protected void apply(StreamQuery query) {
                        if (StringUtil.isNotEmpty(id)) {
                            // TODO: support search by ID
                        }
                        // query.setLimit(1);
                    }
                });

        if (resp.size() == 0) {
            return null;
        }

        RowEntity first = resp.getElements().stream().filter(e -> id.equals(e.getId())).findFirst().orElse(null);
        return first == null ? null : buildProfileTask(first);
    }

    private ProfileTask buildProfileTask(RowEntity data) {
        return ProfileTask.builder()
                .id(data.getId())
                .serviceId(data.getTagValue(ProfileTaskRecord.SERVICE_ID))
                .endpointName(data.getTagValue(ProfileTaskRecord.ENDPOINT_NAME))
                .startTime(((Number) data.getTagValue(ProfileTaskRecord.START_TIME)).longValue())
                .createTime(((Number) data.getTagValue(ProfileTaskRecord.CREATE_TIME)).longValue())
                .duration(((Number) data.getTagValue(ProfileTaskRecord.DURATION)).intValue())
                .minDurationThreshold(((Number) data.getTagValue(ProfileTaskRecord.MIN_DURATION_THRESHOLD)).intValue())
                .dumpPeriod(((Number) data.getTagValue(ProfileTaskRecord.DUMP_PERIOD)).intValue())
                .maxSamplingCount(((Number) data.getTagValue(ProfileTaskRecord.MAX_SAMPLING_COUNT)).intValue())
                .build();
    }
}
