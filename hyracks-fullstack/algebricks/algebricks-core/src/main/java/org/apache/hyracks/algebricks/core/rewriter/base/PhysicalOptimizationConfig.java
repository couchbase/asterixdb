/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.algebricks.core.rewriter.base;

import static org.apache.hyracks.algebricks.core.algebra.operators.physical.AbstractGroupByPOperator.MIN_FRAME_LIMIT_FOR_GROUP_BY;
import static org.apache.hyracks.algebricks.core.algebra.operators.physical.AbstractJoinPOperator.MIN_FRAME_LIMIT_FOR_JOIN;
import static org.apache.hyracks.algebricks.core.algebra.operators.physical.AbstractStableSortPOperator.MIN_FRAME_LIMIT_FOR_SORT;
import static org.apache.hyracks.algebricks.core.algebra.operators.physical.WindowPOperator.MIN_FRAME_LIMIT_FOR_WINDOW;

import java.util.Properties;

import org.apache.hyracks.algebricks.core.config.AlgebricksConfig;

public class PhysicalOptimizationConfig {
    private static final int MB = 1048576;

    private static final String FRAMESIZE = "FRAMESIZE";
    private static final String MAX_FRAMES_EXTERNAL_SORT = "MAX_FRAMES_EXTERNAL_SORT";
    private static final String MAX_FRAMES_EXTERNAL_GROUP_BY = "MAX_FRAMES_EXTERNAL_GROUP_BY";
    private static final String MAX_FRAMES_FOR_JOIN_LEFT_INPUT = "MAX_FRAMES_FOR_JOIN_LEFT_INPUT";
    private static final String MAX_FRAMES_FOR_JOIN = "MAX_FRAMES_FOR_JOIN";
    private static final String MAX_FRAMES_FOR_WINDOW = "MAX_FRAMES_FOR_WINDOW";
    private static final String MAX_FRAMES_FOR_TEXTSEARCH = "MAX_FRAMES_FOR_TEXTSEARCH";
    private static final String FUDGE_FACTOR = "FUDGE_FACTOR";
    private static final String MAX_RECORDS_PER_FRAME = "MAX_RECORDS_PER_FRAME";
    private static final String DEFAULT_HASH_GROUP_TABLE_SIZE = "DEFAULT_HASH_GROUP_TABLE_SIZE";
    private static final String DEFAULT_EXTERNAL_GROUP_TABLE_SIZE = "DEFAULT_EXTERNAL_GROUP_TABLE_SIZE";
    private static final String DEFAULT_IN_MEM_HASH_JOIN_TABLE_SIZE = "DEFAULT_IN_MEM_HASH_JOIN_TABLE_SIZE";
    private static final String SORT_PARALLEL = "SORT_PARALLEL";
    private static final String SORT_SAMPLES = "SORT_SAMPLES";
    private static final String INDEX_ONLY = "INDEX_ONLY";
    private static final String SANITY_CHECK = "SANITY_CHECK";
    private static final String EXTERNAL_FIELD_PUSHDOWN = "EXTERNAL_FIELD_PUSHDOWN";
    private static final String SUBPLAN_MERGE = "SUBPLAN_MERGE";
    private static final String SUBPLAN_NESTEDPUSHDOWN = "SUBPLAN_NESTEDPUSHDOWN";
    private static final String MIN_MEMORY_ALLOCATION = "MIN_MEMORY_ALLOCATION";
    private static final String ARRAY_INDEX = "ARRAY_INDEX";
    private static final String EXTERNAL_SCAN_BUFFER_SIZE = "EXTERNAL_SCAN_BUFFER_SIZE";
    private static final String BATCH_LOOKUP = "BATCH_LOOKUP";
    private static final String CBO = "CBO";
    private static final String CBO_TEST = "CBO_TEST";
    private static final String FORCE_JOIN_ORDER = "FORCE_JOIN_ORDER";
    private static final String QUERY_PLAN_SHAPE = "QUERY_PLAN_SHAPE";
    private static final String COLUMN_FILTER = "COLUMN_FILTER";
    private static final String MIN_SORT_FRAMES = "MIN_SORT_FRAMES";
    private static final String MIN_JOIN_FRAMES = "MIN_JOIN_FRAMES";
    private static final String MIN_GROUP_FRAMES = "MIN_GROUP_FRAMES";
    private static final String MIN_WINDOW_FRAMES = "MIN_WINDOW_FRAMES";
    private static final String MAX_VARIABLE_OCCURRENCES_INLINING = "MAX_VARIABLE_OCCURRENCES_INLINING";

    private static final String ORDER_FIELDS = "ORDERED_FIELDS";

    private final Properties properties = new Properties();

    public PhysicalOptimizationConfig() {
        int frameSize = 32768;
        setInt(FRAMESIZE, frameSize);
        setInt(MAX_FRAMES_EXTERNAL_SORT, (int) (((long) 32 * MB) / frameSize));
        setInt(MAX_FRAMES_EXTERNAL_GROUP_BY, (int) (((long) 32 * MB) / frameSize));

        // use http://www.rsok.com/~jrm/printprimes.html to find prime numbers
        setInt(DEFAULT_HASH_GROUP_TABLE_SIZE, 10485767);
        setInt(DEFAULT_EXTERNAL_GROUP_TABLE_SIZE, 10485767);
        setInt(DEFAULT_IN_MEM_HASH_JOIN_TABLE_SIZE, 10485767);

        setInt(MIN_SORT_FRAMES, MIN_FRAME_LIMIT_FOR_SORT);
        setInt(MIN_JOIN_FRAMES, MIN_FRAME_LIMIT_FOR_JOIN);
        setInt(MIN_GROUP_FRAMES, MIN_FRAME_LIMIT_FOR_GROUP_BY);
        setInt(MIN_WINDOW_FRAMES, MIN_FRAME_LIMIT_FOR_WINDOW);
    }

    public int getFrameSize() {
        return getInt(FRAMESIZE, 32768);
    }

    public void setFrameSize(int frameSize) {
        setInt(FRAMESIZE, frameSize);
    }

    public double getFudgeFactor() {
        return getDouble(FUDGE_FACTOR, 1.3);
    }

    public void setFudgeFactor(double fudgeFactor) {
        setDouble(FUDGE_FACTOR, fudgeFactor);
    }

    public int getMaxRecordsPerFrame() {
        return getInt(MAX_RECORDS_PER_FRAME, 512);
    }

    public void setMaxRecordsPerFrame(int maxRecords) {
        setInt(MAX_RECORDS_PER_FRAME, maxRecords);
    }

    public int getMaxFramesForJoinLeftInput() {
        return getInt(MAX_FRAMES_FOR_JOIN_LEFT_INPUT, -1);
    }

    public void setMaxFramesForJoinLeftInput(int frameLimit) {
        setInt(MAX_FRAMES_FOR_JOIN_LEFT_INPUT, frameLimit);
    }

    public int getMaxFramesForJoin() {
        int frameSize = getFrameSize();
        return getInt(MAX_FRAMES_FOR_JOIN, (int) (64L * MB / frameSize));
    }

    public void setMaxFramesForJoin(int frameLimit) {
        setInt(MAX_FRAMES_FOR_JOIN, frameLimit);
    }

    public int getMaxFramesForGroupBy() {
        int frameSize = getFrameSize();
        return getInt(MAX_FRAMES_EXTERNAL_GROUP_BY, (int) (((long) 256 * MB) / frameSize));
    }

    public void setMaxFramesExternalGroupBy(int frameLimit) {
        setInt(MAX_FRAMES_EXTERNAL_GROUP_BY, frameLimit);
    }

    public int getMaxFramesExternalSort() {
        int frameSize = getFrameSize();
        return getInt(MAX_FRAMES_EXTERNAL_SORT, (int) (((long) 32 * MB) / frameSize));
    }

    public void setMaxFramesExternalSort(int frameLimit) {
        setInt(MAX_FRAMES_EXTERNAL_SORT, frameLimit);
    }

    public int getMaxFramesForWindow() {
        int frameSize = getFrameSize();
        return getInt(MAX_FRAMES_FOR_WINDOW, (int) (((long) 4 * MB) / frameSize));
    }

    public void setMaxFramesForWindow(int frameLimit) {
        setInt(MAX_FRAMES_FOR_WINDOW, frameLimit);
    }

    public int getMaxFramesForTextSearch() {
        int frameSize = getFrameSize();
        return getInt(MAX_FRAMES_FOR_TEXTSEARCH, (int) (((long) 32 * MB) / frameSize));
    }

    public void setMaxFramesForTextSearch(int frameLimit) {
        setInt(MAX_FRAMES_FOR_TEXTSEARCH, frameLimit);
    }

    public void setOrderFields(boolean orderFields) {
        setBoolean(ORDER_FIELDS, orderFields);
    }

    public int getHashGroupByTableSize() {
        return getInt(DEFAULT_HASH_GROUP_TABLE_SIZE, 10485767);
    }

    public void setHashGroupByTableSize(int tableSize) {
        setInt(DEFAULT_HASH_GROUP_TABLE_SIZE, tableSize);
    }

    public int getExternalGroupByTableSize() {
        return getInt(DEFAULT_EXTERNAL_GROUP_TABLE_SIZE, 10485767);
    }

    public void setExternalGroupByTableSize(int tableSize) {
        setInt(DEFAULT_EXTERNAL_GROUP_TABLE_SIZE, tableSize);
    }

    public int getInMemHashJoinTableSize() {
        return getInt(DEFAULT_IN_MEM_HASH_JOIN_TABLE_SIZE, 10485767);
    }

    public void setInMemHashJoinTableSize(int tableSize) {
        setInt(DEFAULT_IN_MEM_HASH_JOIN_TABLE_SIZE, tableSize);
    }

    public int getMinSortFrames() {
        return getInt(MIN_SORT_FRAMES, MIN_FRAME_LIMIT_FOR_SORT);
    }

    public void setMinSortFrames(int minSortFrames) {
        if (minSortFrames < MIN_FRAME_LIMIT_FOR_SORT) {
            throw new IllegalArgumentException(
                    "Minimum sort frames is " + MIN_FRAME_LIMIT_FOR_SORT + ", got " + minSortFrames);
        }
        setInt(MIN_SORT_FRAMES, minSortFrames);
    }

    public int getMinJoinFrames() {
        return getInt(MIN_JOIN_FRAMES, MIN_FRAME_LIMIT_FOR_JOIN);
    }

    public void setMinJoinFrames(int minJoinFrames) {
        if (minJoinFrames < MIN_FRAME_LIMIT_FOR_JOIN) {
            throw new IllegalArgumentException(
                    "Minimum join frames is " + MIN_FRAME_LIMIT_FOR_JOIN + ", got " + minJoinFrames);
        }
        setInt(MIN_JOIN_FRAMES, minJoinFrames);
    }

    public int getMinGroupFrames() {
        return getInt(MIN_GROUP_FRAMES, MIN_FRAME_LIMIT_FOR_GROUP_BY);
    }

    public void setMinGroupFrames(int minGroupFrames) {
        if (minGroupFrames < MIN_FRAME_LIMIT_FOR_GROUP_BY) {
            throw new IllegalArgumentException(
                    "Minimum group frames is " + MIN_FRAME_LIMIT_FOR_GROUP_BY + ", got " + minGroupFrames);
        }
        setInt(MIN_GROUP_FRAMES, minGroupFrames);
    }

    public int getMinWindowFrames() {
        return getInt(MIN_WINDOW_FRAMES, MIN_FRAME_LIMIT_FOR_WINDOW);
    }

    public void setMinWindowFrames(int minWindowFrames) {
        if (minWindowFrames < MIN_FRAME_LIMIT_FOR_WINDOW) {
            throw new IllegalArgumentException(
                    "Minimum window frames is " + MIN_FRAME_LIMIT_FOR_WINDOW + ", got " + minWindowFrames);
        }
        setInt(MIN_WINDOW_FRAMES, minWindowFrames);
    }

    public boolean getSortParallel() {
        return getBoolean(SORT_PARALLEL, AlgebricksConfig.SORT_PARALLEL_DEFAULT);
    }

    public boolean isOrderField() {
        return getBoolean(ORDER_FIELDS, AlgebricksConfig.ORDERED_FIELDS);
    }

    public void setSortParallel(boolean sortParallel) {
        setBoolean(SORT_PARALLEL, sortParallel);
    }

    public int getSortSamples() {
        return getInt(SORT_SAMPLES, AlgebricksConfig.SORT_SAMPLES_DEFAULT);
    }

    public void setSortSamples(int sortSamples) {
        setInt(SORT_SAMPLES, sortSamples);
    }

    public void setIndexOnly(boolean indexOnly) {
        setBoolean(INDEX_ONLY, indexOnly);
    }

    public boolean isIndexOnly() {
        return getBoolean(INDEX_ONLY, AlgebricksConfig.INDEX_ONLY_DEFAULT);
    }

    public void setSanityCheckEnabled(boolean sanityCheck) {
        setBoolean(SANITY_CHECK, sanityCheck);
    }

    public boolean isSanityCheckEnabled() {
        return getBoolean(SANITY_CHECK, AlgebricksConfig.SANITYCHECK_DEFAULT);
    }

    public boolean isExternalFieldPushdown() {
        return getBoolean(EXTERNAL_FIELD_PUSHDOWN, AlgebricksConfig.EXTERNAL_FIELD_PUSHDOWN_DEFAULT);
    }

    public void setExternalFieldPushdown(boolean externalFieldPushDown) {
        setBoolean(EXTERNAL_FIELD_PUSHDOWN, externalFieldPushDown);
    }

    public boolean getSubplanMerge() {
        return getBoolean(SUBPLAN_MERGE, AlgebricksConfig.SUBPLAN_MERGE_DEFAULT);
    }

    public void setSubplanMerge(boolean value) {
        setBoolean(SUBPLAN_MERGE, value);
    }

    public boolean getSubplanNestedPushdown() {
        return getBoolean(SUBPLAN_NESTEDPUSHDOWN, AlgebricksConfig.SUBPLAN_NESTEDPUSHDOWN_DEFAULT);
    }

    public void setSubplanNestedPushdown(boolean value) {
        setBoolean(SUBPLAN_NESTEDPUSHDOWN, value);
    }

    public boolean getMinMemoryAllocation() {
        return getBoolean(MIN_MEMORY_ALLOCATION, AlgebricksConfig.MIN_MEMORY_ALLOCATION_DEFAULT);
    }

    public void setMinMemoryAllocation(boolean value) {
        setBoolean(MIN_MEMORY_ALLOCATION, value);
    }

    public boolean isArrayIndexEnabled() {
        return getBoolean(ARRAY_INDEX, AlgebricksConfig.ARRAY_INDEX_DEFAULT);
    }

    public void setArrayIndexEnabled(boolean arrayIndex) {
        setBoolean(ARRAY_INDEX, arrayIndex);
    }

    public int getExternalScanBufferSize() {
        return getInt(EXTERNAL_SCAN_BUFFER_SIZE, AlgebricksConfig.EXTERNAL_SCAN_BUFFER_SIZE);
    }

    public boolean getCBOMode() {
        return getBoolean(CBO, AlgebricksConfig.CBO_DEFAULT);
    }

    public boolean getCBOTestMode() {
        return getBoolean(CBO_TEST, AlgebricksConfig.CBO_TEST_DEFAULT);
    }

    public boolean getForceJoinOrderMode() {
        return getBoolean(FORCE_JOIN_ORDER, AlgebricksConfig.FORCE_JOIN_ORDER_DEFAULT);
    }

    public String getQueryPlanShapeMode() {
        String queryPlanShapeMode = getString(QUERY_PLAN_SHAPE, AlgebricksConfig.QUERY_PLAN_SHAPE_DEFAULT);
        if (!(queryPlanShapeMode.equals(AlgebricksConfig.QUERY_PLAN_SHAPE_ZIGZAG)
                || queryPlanShapeMode.equals(AlgebricksConfig.QUERY_PLAN_SHAPE_LEFTDEEP)
                || queryPlanShapeMode.equals(AlgebricksConfig.QUERY_PLAN_SHAPE_RIGHTDEEP))) {
            return AlgebricksConfig.QUERY_PLAN_SHAPE_DEFAULT;
        }
        return queryPlanShapeMode;
    }

    public void setCBOMode(boolean cbo) {
        setBoolean(CBO, cbo);
    }

    public void setCBOTestMode(boolean cboTest) {
        setBoolean(CBO_TEST, cboTest);
    }

    public void setForceJoinOrderMode(boolean forceJoinOrder) {
        setBoolean(FORCE_JOIN_ORDER, forceJoinOrder);
    }

    public void setQueryPlanShapeMode(String queryPlanShape) {
        setString(QUERY_PLAN_SHAPE, queryPlanShape);
    }

    public boolean isBatchLookupEnabled() {
        return getBoolean(BATCH_LOOKUP, AlgebricksConfig.BATCH_LOOKUP_DEFAULT);
    }

    public void setBatchLookup(boolean batchedLookup) {
        setBoolean(BATCH_LOOKUP, batchedLookup);
    }

    public void setExternalScanBufferSize(int bufferSize) {
        setInt(EXTERNAL_SCAN_BUFFER_SIZE, bufferSize);
    }

    public void setColumnFilter(boolean columnFilter) {
        setBoolean(COLUMN_FILTER, columnFilter);
    }

    public boolean isColumnFilterEnabled() {
        return getBoolean(COLUMN_FILTER, AlgebricksConfig.COLUMN_FILTER_DEFAULT);
    }

    public void setExtensionProperty(String property, Object value) {
        if (value instanceof Integer) {
            setInt(property, (Integer) value);
        } else if (value instanceof Double) {
            setDouble(property, (Double) value);
        } else if (value instanceof Boolean) {
            setBoolean(property, (Boolean) value);
        } else if (value instanceof String) {
            setString(property, (String) value);
        } else {
            throw new IllegalArgumentException();
        }
    }

    public Object getExtensionProperty(String property) {
        return properties.getProperty(property);
    }

    public int getMaxVariableOccurrencesForInlining() {
        return getInt(MAX_VARIABLE_OCCURRENCES_INLINING, AlgebricksConfig.MAX_VARIABLE_OCCURRENCES_INLINING_DEFAULT);
    }

    public void setMaxVariableOccurrencesForInlining(int maxVariableOccurrencesForInlining) {
        setInt(MAX_VARIABLE_OCCURRENCES_INLINING, maxVariableOccurrencesForInlining);
    }

    private void setInt(String property, int value) {
        properties.setProperty(property, Integer.toString(value));
    }

    private int getInt(String property, int defaultValue) {
        String value = properties.getProperty(property);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private void setDouble(String property, double value) {
        properties.setProperty(property, Double.toString(value));
    }

    private double getDouble(String property, double defaultValue) {
        String value = properties.getProperty(property);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    private void setBoolean(String property, boolean value) {
        properties.setProperty(property, Boolean.toString(value));
    }

    private boolean getBoolean(String property, boolean defaultValue) {
        String value = properties.getProperty(property);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private void setString(String property, String value) {
        properties.setProperty(property, value);
    }

    private String getString(String property, String defaultValue) {
        String value = properties.getProperty(property);
        return value == null ? defaultValue : value;
    }
}
