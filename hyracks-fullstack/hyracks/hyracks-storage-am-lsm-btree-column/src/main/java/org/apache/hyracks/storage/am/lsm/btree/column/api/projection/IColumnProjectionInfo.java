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
 * Gets information about the requested columns
 */
public interface IColumnProjectionInfo {
    /**
     * @param ordinal position of the requested column
     * @return column index given the ordinal number of the requested column
     */
    int getColumnIndex(int ordinal);

    /**
     * @return total number of requested columns
     */
    int getNumberOfProjectedColumns();

    /**
     * @return number of primary keys
     */
    int getNumberOfPrimaryKeys();

    /**
     * @param ordinal position of the filtered column
     * @return column index given the ordinal number of the filtered column
     */
    int getFilteredColumnIndex(int ordinal);

    /**
     * @return number of filtered columns
     */
    int getNumberOfFilteredColumns();

    /**
     * @return the type of {@link IColumnTupleProjector} that created this projection info
     */
    ColumnProjectorType getProjectorType();

    /**
     * A key-only view of this projection, for accessors that only ask "does this key exist in this component?" —
     * the sample cursor's liveness probe. Existence is settled by the PK binary search
     * ({@code IColumnTupleIterator#findTupleIndex}, PK values only), so dropping the non-key columns cannot
     * change an answer; it only avoids pinning mega-pages that are released unread. See
     * {@code ColumnBTreeExistencePointSearchCursor}.
     * <p>
     * A reduced view <b>must</b> report {@link ColumnProjectorType#EXISTENCE}. Zero projected columns is not
     * enough on cloud storage: {@code CloudColumnReadContext} branches on the projector type first, and for
     * {@link ColumnProjectorType#MERGE} pins the whole mega-leaf without consulting the projected set at all.
     * <p>
     * Returning {@code this} — the default — means "no reduced view available": correct, just slower. An
     * implementation that no existence probe can reach may instead throw {@link UnsupportedOperationException},
     * so the assumption stays checked rather than silently supporting a path nothing exercises.
     *
     * @return a projection reporting zero projected columns, zero filtered columns and
     *         {@link ColumnProjectorType#EXISTENCE}, or {@code this}
     * @throws UnsupportedOperationException if this projection is never the one an existence probe is built from
     */
    default IColumnProjectionInfo createExistenceOnlyProjectionInfo() {
        return this;
    }
}
