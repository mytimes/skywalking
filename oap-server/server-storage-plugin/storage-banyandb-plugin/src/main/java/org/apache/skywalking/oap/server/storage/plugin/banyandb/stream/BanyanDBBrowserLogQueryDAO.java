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
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.skywalking.banyandb.v1.client.RowEntity;
import org.apache.skywalking.banyandb.v1.client.StreamQuery;
import org.apache.skywalking.banyandb.v1.client.StreamQueryResponse;
import org.apache.skywalking.banyandb.v1.client.TimestampRange;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.browser.manual.errorlog.BrowserErrorLogRecord;
import org.apache.skywalking.oap.server.core.browser.source.BrowserErrorCategory;
import org.apache.skywalking.oap.server.core.query.type.BrowserErrorLog;
import org.apache.skywalking.oap.server.core.query.type.BrowserErrorLogs;
import org.apache.skywalking.oap.server.core.query.type.ErrorCategory;
import org.apache.skywalking.oap.server.core.storage.query.IBrowserLogQueryDAO;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.BanyanDBStorageClient;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * {@link org.apache.skywalking.oap.server.core.browser.manual.errorlog.BrowserErrorLogRecord} is a stream
 */
public class BanyanDBBrowserLogQueryDAO extends AbstractBanyanDBDAO implements IBrowserLogQueryDAO {
    private static final Set<String> TAGS = ImmutableSet.of(BrowserErrorLogRecord.SERVICE_ID,
            BrowserErrorLogRecord.SERVICE_VERSION_ID, BrowserErrorLogRecord.PAGE_PATH_ID,
            BrowserErrorLogRecord.ERROR_CATEGORY, BrowserErrorLogRecord.DATA_BINARY);

    public BanyanDBBrowserLogQueryDAO(BanyanDBStorageClient client) {
        super(client);
    }

    @Override
    public BrowserErrorLogs queryBrowserErrorLogs(String serviceId, String serviceVersionId, String pagePathId, BrowserErrorCategory category, long startSecondTB, long endSecondTB, int limit, int from) throws IOException {
        TimestampRange tsRange = null;
        if (startSecondTB > 0 && endSecondTB > 0) {
            tsRange = new TimestampRange(TimeBucket.getTimestamp(startSecondTB), TimeBucket.getTimestamp(endSecondTB));
        }

        StreamQueryResponse resp = query(BrowserErrorLogRecord.INDEX_NAME, TAGS,
                tsRange, new QueryBuilder<StreamQuery>() {
                    @Override
                    public void apply(StreamQuery query) {
                        if (StringUtil.isNotEmpty(serviceId)) {
                            query.and(eq(BrowserErrorLogRecord.SERVICE_ID, serviceId));
                        }

                        if (StringUtil.isNotEmpty(serviceVersionId)) {
                            query.and(eq(BrowserErrorLogRecord.SERVICE_VERSION_ID, serviceVersionId));
                        }

                        if (StringUtil.isNotEmpty(pagePathId)) {
                            query.and(eq(BrowserErrorLogRecord.PAGE_PATH_ID, pagePathId));
                        }

                        if (Objects.nonNull(category)) {
                            query.and(eq(BrowserErrorLogRecord.ERROR_CATEGORY, category.getValue()));
                        }

                        query.setOffset(from);
                        query.setLimit(limit);
                    }
                });

        BrowserErrorLogs logs = new BrowserErrorLogs();
        logs.setTotal(resp.size());

        for (final RowEntity rowEntity : resp.getElements()) {
            final byte[] dataBinary = rowEntity.getTagValue(BrowserErrorLogRecord.DATA_BINARY);
            if (dataBinary != null && dataBinary.length > 0) {
                BrowserErrorLog log = parserDataBinary(dataBinary);
                logs.getLogs().add(log);
            }
        }
        return logs;
    }

    /**
     * TODO: merge the default method in the interface
     */
    private BrowserErrorLog parserDataBinary(
            byte[] dataBinary) {
        try {
            BrowserErrorLog log = new BrowserErrorLog();
            org.apache.skywalking.apm.network.language.agent.v3.BrowserErrorLog browserErrorLog = org.apache.skywalking.apm.network.language.agent.v3.BrowserErrorLog
                    .parseFrom(dataBinary);

            log.setService(browserErrorLog.getService());
            log.setServiceVersion(browserErrorLog.getServiceVersion());
            log.setTime(browserErrorLog.getTime());
            log.setPagePath(browserErrorLog.getPagePath());
            log.setCategory(ErrorCategory.valueOf(browserErrorLog.getCategory().name().toUpperCase()));
            log.setGrade(browserErrorLog.getGrade());
            log.setMessage(browserErrorLog.getMessage());
            log.setLine(browserErrorLog.getLine());
            log.setCol(browserErrorLog.getCol());
            log.setStack(browserErrorLog.getStack());
            log.setErrorUrl(browserErrorLog.getErrorUrl());
            log.setFirstReportedError(browserErrorLog.getFirstReportedError());

            return log;
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }
}
