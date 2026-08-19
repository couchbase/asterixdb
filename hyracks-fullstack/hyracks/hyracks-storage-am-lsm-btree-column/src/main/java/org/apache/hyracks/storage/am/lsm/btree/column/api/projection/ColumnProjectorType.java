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
package org.apache.hyracks.storage.am.lsm.btree.column.api.projection;

/**
 * What an {@link IColumnProjectionInfo} is going to be read for.
 * <p>
 * Compared with {@code ==} only — nothing switches on this type, so a new constant cannot fall through a
 * {@code default}. Three sites branch on it: {@code CloudColumnReadContext} (which pages to pin),
 * {@code CloudMegaPageReadContext} (whether to persist cloud-read pages locally) and
 * {@code CloudColumnIndexDiskCacheManager} (whether to feed the column-eviction planner). On-premise
 * ({@code DefaultColumnReadContext}) ignores it.
 */
public enum ColumnProjectorType {
    /**
     * Reads <b>every</b> column, so the cloud read context pins the whole mega-leaf in one request instead of
     * coalescing per-column ranges.
     */
    MERGE,
    QUERY,
    MODIFY,
    /**
     * <b>Existence-only</b>: answers "is this primary key in this component?" and materializes no column value.
     * From {@link IColumnProjectionInfo#createExistenceOnlyProjectionInfo()}, used only by the sample cursor's
     * liveness probe. Pins page zero and its segments — the PK binary search reads key values there — and
     * nothing more, i.e. it pins like {@code QUERY} with an empty projected set.
     * <p>
     * Its own constant rather than an existing one because both alternatives misbehave: {@link #MERGE} makes
     * {@code CloudColumnReadContext#prepareColumns} pin the whole mega-leaf, the exact I/O being avoided, and
     * {@link #QUERY} feeds the column-eviction planner an access record
     * ({@code CloudColumnIndexDiskCacheManager#createReadContext}) — a zero-column probe must not register as a
     * query that touched no columns.
     */
    EXISTENCE
}
