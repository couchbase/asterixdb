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

package org.apache.hyracks.storage.am.vector.impls;

import static org.apache.hyracks.storage.common.buffercache.context.read.DefaultBufferCacheReadContextProvider.NEW;

import java.util.HashSet;
import java.util.Set;

import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.storage.am.common.api.IPageManager;
import org.apache.hyracks.storage.am.common.api.ITreeIndexFrameFactory;
import org.apache.hyracks.storage.am.common.api.ITreeIndexTupleWriter;
import org.apache.hyracks.storage.am.common.frames.FrameOpSpaceStatus;
import org.apache.hyracks.storage.am.vector.api.IVTreeDataFrame;
import org.apache.hyracks.storage.am.vector.api.IVTreeMetadataFrame;
import org.apache.hyracks.storage.am.vector.frames.VTreeDataFrame;
import org.apache.hyracks.storage.am.vector.frames.VTreeMetadataFrame;
import org.apache.hyracks.storage.am.vector.utils.VTreeDataTupleAccessor;
import org.apache.hyracks.storage.am.vector.utils.VTreeMetadataTupleAccessor;
import org.apache.hyracks.storage.common.buffercache.IBufferCache;
import org.apache.hyracks.storage.common.buffercache.ICachedPage;
import org.apache.hyracks.storage.common.file.BufferedFileHandle;
import org.apache.hyracks.util.annotations.AiProvenance;

/**
 * Page-level mutation of a {@link VTree}'s directory and data pages: the half of the write path below
 * cluster selection. Extracted from {@code VTree} so that page mutation can be exercised — and reasoned
 * about — without the tree navigation, static-structure attachment and accessor machinery it used to sit
 * beside.
 * <p>
 * <b>What it does and does not know.</b> Everything here operates on a directory-page chain that a caller
 * has already chosen: the walk over that chain, the distance-ordered placement within a data page, page
 * splits, and the directory bookkeeping each split implies. It knows nothing about centroids, distance
 * metrics, cross-pollination, the static structure, or how a cluster was picked. The seam is exactly two
 * entry points — {@link #insertIntoDataPages} and {@link #tryPhysicalDelete} — both taking the directory
 * page id the caller resolved, which is why the split is a move rather than a redesign.
 * <p>
 * <b>Latching contract.</b> Both entry points pin and latch the pages they touch and release them before
 * returning; nothing is held across a call. The metadata-mutation helpers are the exception and are
 * deliberately so: they operate on the directory frame their caller already holds write-latched, which
 * {@link #requireLatchedMetadataFrame} asserts rather than documents. The frames themselves live on the
 * caller's {@link VTreeOpContext} and are shared, so an instance of this class holds no per-operation
 * state and is safe to share across threads exactly as far as the op-contexts are kept separate.
 */
// Not a record: a stateless collaborator, not a data carrier. Record accessors would publish
// bufferCache/freePageManager as package API, and record visibility cannot be narrowed.
@SuppressWarnings("ClassCanBeRecord")
@AiProvenance(agent = AiProvenance.Agent.CLAUDE_OPUS_5, tool = AiProvenance.Tool.CLAUDE_CODE_UI, contributionKind = AiProvenance.ContributionKind.REFACTORED, notes = "Extracted verbatim from VTree; behaviour unchanged")
class VTreePageMutator {

    private final IBufferCache bufferCache;
    private final IPageManager freePageManager;
    private final ITreeIndexFrameFactory metadataFrameFactory;
    /** Fixes the data-tuple layout, and with it which field the primary key starts at. */
    private final boolean quantized;

    VTreePageMutator(IBufferCache bufferCache, IPageManager freePageManager,
            ITreeIndexFrameFactory metadataFrameFactory, boolean quantized) {
        this.bufferCache = bufferCache;
        this.freePageManager = freePageManager;
        this.metadataFrameFactory = metadataFrameFactory;
        this.quantized = quantized;
    }

    /**
     * Insert vector data into data pages via metadata pages. This method traverses through all linked metadata pages to
     * find the appropriate data page.
     */
    void insertIntoDataPages(long metadataPageId, double[] vector, double distance, int centroidId,
            ITupleReference originalTuple, VTreeOpContext ctx, int fileId) throws HyracksDataException {

        // Traverse through all linked directory (metadata) pages to find the appropriate data page.
        // Guard against a corrupted next-page chain that loops back on itself by tracking the page
        // ids already visited; a repeat is a genuine cycle, not just a long-but-valid chain.
        //
        // Concurrency: this is a forward-only walk that write-latches one directory page at a time and
        // releases it before pinning the next (it does not hold the whole chain). That is safe because
        // the directory chain is globally max_distance-ascending and only ever grows by in-place split
        // (VTreeMetadataFrame keeps entries sorted; handleMetadataPageOverflow moves the upper entries to
        // a new page linked *after* the current one — pages are never removed or reordered). Each page is
        // read under its own latch, so the walker sees a consistent snapshot per page. Since this walker
        // advanced past page N only because the record's distance exceeded every entry on N, a concurrent
        // split of N (which can only relocate entries <= N's max to a new page inserted between N and its
        // old successor) cannot hold this record's band — so following N's already-read successor pointer
        // never skips the correct page. The walker therefore always converges on the right band by moving
        // forward. tryPhysicalDelete relies on the same invariant.
        long currentMetadataPageId = metadataPageId;
        Set<Long> visitedMetadataPageIds = new HashSet<>();

        while (currentMetadataPageId != -1) {
            if (!visitedMetadataPageIds.add(currentMetadataPageId)) {
                throw HyracksDataException.create(ErrorCode.ILLEGAL_STATE,
                        "Cycle detected in directory page chain starting at page " + metadataPageId
                                + " (revisited page " + currentMetadataPageId + ")");
            }
            ICachedPage metadataPage =
                    bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, (int) currentMetadataPageId));
            ctx.setMetadataPageId(currentMetadataPageId);
            boolean latched = false;
            try {
                metadataPage.acquireWriteLatch();
                latched = true;
                ctx.getMetadataFrame().setPage(metadataPage);

                // Determine if this is the last directory page in the chain
                int nextMetadataPageId = ctx.getMetadataFrame().getNextPage();
                boolean isLastInChain = (nextMetadataPageId == VTreeDataTupleAccessor.NO_NEXT_PAGE);

                // Try to find appropriate data page based on distance.
                // Only uses catch-all (last data page) on the last directory page;
                // otherwise returns -1 so we traverse to the next directory page.
                long targetDataPageId = findDataPageInMetadataPage(ctx.getMetadataFrame(), distance, isLastInChain);

                if (targetDataPageId != -1) {
                    // Found appropriate data page - insert into it. insertIntoDataPage() either inserts
                    // directly, compacts and inserts, or splits the page; it never reports "no room".
                    insertIntoDataPage(targetDataPageId, vector, distance, centroidId, originalTuple, ctx, fileId);
                    return;
                }

                // No match on this directory page
                if (isLastInChain) {
                    // Last page in chain - create new data page
                    handleDataPageOverflow(currentMetadataPageId, vector, distance, centroidId, originalTuple, ctx,
                            fileId);
                    return;
                }

                // Traverse to next directory page
                currentMetadataPageId = nextMetadataPageId;

            } finally {
                if (latched) {
                    metadataPage.releaseWriteLatch(true);
                }
                bufferCache.unpin(metadataPage);
            }
        }
    }

    /**
     * Find the appropriate data page in a specific metadata page based on distance. This searches for a data page that
     * can accommodate the given distance.
     * <p>
     * Returns last data page as catch-all when distance > all max_distance values.
     * This is needed for BOTH insertion and deletion:
     * - Matter insertion: Vectors with distance > all max values go into last page (catch-all)
     * - Delete-marker tuple insertion: Same as matter insertion - uses last page as catch-all
     * - Physical deletion: To find those vectors, we must check the last page (same catch-all)
     * <p>
     * The last page dynamically expands and metadata max_distance is updated automatically
     * via updateMetadataMaxDistanceIfNeeded() in the insertion path.
     *
     * @param metadataFrame The metadata frame to search
     * @param distance The distance to search for
     * @return Data page ID, or -1 if metadata is empty
     */
    private long findDataPageInMetadataPage(IVTreeMetadataFrame metadataFrame, double distance, boolean isLastInChain)
            throws HyracksDataException {

        int tupleCount = metadataFrame.getTupleCount();

        // Entries are kept sorted by max_distance ascending (VTreeMetadataFrame invariant, maintained
        // by findInsertPosition on every metadata insert). Binary-search the first entry whose
        // max_distance >= distance (i.e. the first data page whose range covers this distance) instead
        // of an O(n) scan. This matches the previous linear "first distance <= maxDistance" result.
        int lo = 0;
        int hi = tupleCount; // half-open [lo, hi)
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (metadataFrame.getMaxDistance(mid) >= distance) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        if (lo < tupleCount) {
            return metadataFrame.getDataPagePointer(lo);
        }

        // Only use catch-all (last data page) on the last directory page in the chain.
        // For non-last pages, return -1 so the caller traverses to the next directory page.
        if (isLastInChain && tupleCount > 0) {
            return metadataFrame.getDataPagePointer(tupleCount - 1);
        }

        return -1; // No match on this page (or empty)

    }

    /**
     * Insert into a specific data page. The page always absorbs the tuple: with contiguous free space it is
     * inserted directly, with fragmented free space the page is compacted first, and with no free space the
     * page is split (the tuple then lands in whichever half covers its distance). Any other space status is a
     * frame-level invariant violation and is reported as {@code ILLEGAL_STATE}.
     */
    private void insertIntoDataPage(long dataPageId, double[] vector, double distance, int centroidId,
            ITupleReference originalTuple, VTreeOpContext ctx, int fileId) throws HyracksDataException {

        ICachedPage dataPage = bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, (int) dataPageId));

        boolean latched = false;
        try {
            dataPage.acquireWriteLatch();
            latched = true;
            ctx.getDataFrame().setPage(dataPage);

            // Create data tuple: <distance, centroidId, vector, PK>
            // Pass context so buildDataTuple can check operation type and encode a delete-polarity tuple
            // if DELETE (the encoding is decided by the caller-supplied frame's tuple writer)
            ITupleReference dataTuple =
                    ctx.getDataTupleBuilder().buildDataTuple(vector, distance, centroidId, originalTuple);

            // Check if there's space for the tuple
            FrameOpSpaceStatus spaceStatus = ctx.getDataFrame().hasSpaceInsert(dataTuple);

            switch (spaceStatus) {
                case SUFFICIENT_CONTIGUOUS_SPACE:
                    insertSortedIntoDataPage(dataTuple, distance, dataPageId, originalTuple, ctx);
                    return;
                case SUFFICIENT_SPACE:
                    // Fix bug-vtree-delete-frame-corruption: reclaimable space exists but is fragmented
                    // (FREE_SPACE_OFFSET has been pushed past the slot region by prior inserts whose
                    // deletes only updated TOTAL_FREE_SPACE_OFFSET). Compact first to reset
                    // FREE_SPACE_OFFSET to a safe high-water mark, then insert. Matches the canonical
                    // BTreeNSMLeafFrame pattern (BTree.java:309-315).
                    ctx.getDataFrame().compact();
                    insertSortedIntoDataPage(dataTuple, distance, dataPageId, originalTuple, ctx);
                    return;
                case INSUFFICIENT_SPACE:
                    // Handle overflow by splitting the data page (split recomputes the insertion index
                    // in whichever half the tuple lands, so no position needs to be passed in).
                    splitDataPageMaintainOrder(ctx.getMetadataPageId(), dataPageId, dataTuple, ctx, fileId);
                    return;

                default:
                    throw HyracksDataException.create(ErrorCode.ILLEGAL_STATE, "Unexpected FrameOpSpaceStatus "
                            + spaceStatus + " from VTreeDataFrame.hasSpaceInsert on data page " + dataPageId);
            }

        } finally {
            if (latched) {
                dataPage.releaseWriteLatch(true);
            }
            bufferCache.unpin(dataPage);
        }
    }

    /**
     * Insert a data tuple into the currently-latched data frame at its distance-sorted position, fire the
     * modification callback, bump the page LSN, and grow the catch-all page's metadata max_distance if this
     * distance is a new maximum. Shared by the contiguous-space and post-compaction insert paths in
     * {@link #insertIntoDataPage}.
     */
    private void insertSortedIntoDataPage(ITupleReference dataTuple, double distance, long dataPageId,
            ITupleReference originalTuple, VTreeOpContext ctx) throws HyracksDataException {
        int insertIndex = ((VTreeDataFrame) ctx.getDataFrame()).findInsertPosition(distance);
        ctx.getDataFrame().insert(dataTuple, insertIndex);
        ctx.getModificationCallback().found(null, originalTuple);
        ctx.getDataFrame().setPageLsn(ctx.getDataFrame().getPageLsn() + 1);
        updateMetadataMaxDistanceIfNeeded(ctx.getMetadataPageId(), dataPageId, distance, ctx);
    }

    /**
     * Split data page while maintaining distance-based ordering.
     */
    private void splitDataPageMaintainOrder(long metadataPageId, long dataPageId, ITupleReference newTuple,
            VTreeOpContext ctx, int fileId) throws HyracksDataException {

        // Create new data page for split
        int newDataPageId = freePageManager.takePage(ctx.getMetaFrame());
        ICachedPage newDataPage = bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, newDataPageId), NEW);

        boolean latched = false;
        try {
            newDataPage.acquireWriteLatch();
            latched = true;
            VTreeDataFrame newFrame = (VTreeDataFrame) ctx.getDataFrameFactory().createFrame();
            newFrame.setPage(newDataPage);
            newFrame.initBuffer((byte) 0);

            // Use the frame's split method (following BTree pattern)
            ctx.getDataFrame().split(newFrame, newTuple);

            // Update page links (maintain linked list structure)
            int originalNextPage = ctx.getDataFrame().getNextPage();
            ctx.getDataFrame().setNextPage(newDataPageId);
            newFrame.setNextPage(originalNextPage);

            // Bump both pages past the source page's prior LSN. Page LSNs are not currently
            // consulted for recovery on these LSM-component pages (no readers in this codebase),
            // but keeping the value monotonic mirrors the increment pattern used elsewhere in
            // this class and avoids the non-monotonicity of System.currentTimeMillis().
            long currentLsn = ctx.getDataFrame().getPageLsn() + 1;
            ctx.getDataFrame().setPageLsn(currentLsn);
            newFrame.setPageLsn(currentLsn);

            // Update metadata to reflect the split
            updateMetadataAfterDataSplit(metadataPageId, dataPageId, newDataPageId, ctx, fileId);

        } finally {
            if (latched) {
                newDataPage.releaseWriteLatch(true);
            }
            bufferCache.unpin(newDataPage);
        }
    }

    /**
     * Update metadata page after data page split.
     * Updates BOTH original page's maxDistance and adds new page's entry.
     */
    private void updateMetadataAfterDataSplit(long targetMetadataPageId, long originalDataPageId, int newDataPageId,
            VTreeOpContext ctx, int fileId) throws HyracksDataException {
        if (targetMetadataPageId == -1) {
            throw HyracksDataException.create(ErrorCode.ILLEGAL_STATE,
                    "updateMetadataAfterDataSplit called without a metadata page id (originalDataPageId="
                            + originalDataPageId + ")");
        }

        // Read each page's post-split max distance (original shrank, new page is the spilled-off tail).
        double originalPageMaxDistance = readMaxDistanceInDataPage(originalDataPageId, ctx, fileId);
        double newPageMaxDistance = readMaxDistanceInDataPage(newDataPageId, ctx, fileId);

        // Update ORIGINAL page's maxDistance in metadata (decreased after split)
        forceUpdateMetadataMaxDistance(targetMetadataPageId, originalDataPageId, originalPageMaxDistance, ctx);

        // Add NEW page's metadata entry
        updateMetadataWithNewDataPage(targetMetadataPageId, newDataPageId, newPageMaxDistance, ctx, fileId);
    }

    /**
     * Read the maximum distance-to-centroid stored in a data page. Data-page tuples are kept sorted by
     * distance ascending, so the last tuple carries the page's max; an empty page reports {@code 0.0}.
     * Pins and read-latches the page for the duration.
     */
    private double readMaxDistanceInDataPage(long dataPageId, VTreeOpContext ctx, int fileId)
            throws HyracksDataException {
        ICachedPage dataPage = bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, (int) dataPageId));
        boolean latched = false;
        try {
            dataPage.acquireReadLatch();
            latched = true;
            IVTreeDataFrame dataFrame = (IVTreeDataFrame) ctx.getDataFrameFactory().createFrame();
            dataFrame.setPage(dataPage);
            int tupleCount = dataFrame.getTupleCount();
            return tupleCount > 0 ? dataFrame.getDistanceToCentroid(tupleCount - 1) : 0.0;
        } finally {
            if (latched) {
                dataPage.releaseReadLatch();
            }
            bufferCache.unpin(dataPage);
        }
    }

    /**
     * Try to physically delete a tuple from data pages. Searches through metadata
     * pages to find the tuple and delete it. Returns true if found and deleted,
     * false if not found (caller should insert a delete-marker tuple).
     * <p>
     * Uses binary comparison for primary key matching - no type assumption.
     */
    boolean tryPhysicalDelete(long metadataPageId, double distance, byte[] primaryKey, ITupleReference originalTuple,
            VTreeOpContext ctx, int fileId) throws HyracksDataException {

        // Traverse through all linked directory (metadata) pages
        long currentMetadataPageId = metadataPageId;

        while (currentMetadataPageId != -1) {
            ICachedPage metadataPage =
                    bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, (int) currentMetadataPageId));

            boolean metadataLatched = false;
            try {
                metadataPage.acquireReadLatch();
                metadataLatched = true;
                ctx.getMetadataFrame().setPage(metadataPage);

                // Determine if this is the last directory page in the chain
                int nextMetadataPageId = ctx.getMetadataFrame().getNextPage();
                boolean isLastInChain = (nextMetadataPageId == VTreeDataTupleAccessor.NO_NEXT_PAGE);

                // Find appropriate data page based on distance
                long targetDataPageId = findDataPageInMetadataPage(ctx.getMetadataFrame(), distance, isLastInChain);

                if (targetDataPageId != -1) {
                    // Try physical deletion in this data page
                    ICachedPage dataPage =
                            bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, (int) targetDataPageId));

                    boolean dataLatched = false;
                    try {
                        dataPage.acquireWriteLatch();
                        dataLatched = true;
                        ctx.getDataFrame().setPage(dataPage);

                        // Search for tuple by distance + PK (uses binary comparison internally)
                        int pkFieldIndex = VTreeDataTupleAccessor.getPkStartField(quantized);
                        int tupleIndex = ((VTreeDataFrame) ctx.getDataFrame())
                                .findTupleByDistanceAndPrimaryKey(distance, primaryKey, pkFieldIndex);

                        if (tupleIndex >= 0) {
                            // Found: findTupleByDistanceAndPrimaryKey only returns an index whose PK equals
                            // primaryKey (binary compare), and the data page stays write-latched here, so the
                            // match cannot change under us — no re-check is needed. Physically delete it.
                            ctx.getDataFrame().delete(originalTuple, tupleIndex);
                            return true;
                        }

                        // Not found in this data page
                    } finally {
                        if (dataLatched) {
                            dataPage.releaseWriteLatch(true);
                        }
                        bufferCache.unpin(dataPage);
                    }
                }

                // No match or not found - check next directory page
                if (isLastInChain) {
                    break; // End of chain
                }
                currentMetadataPageId = nextMetadataPageId;

            } finally {
                if (metadataLatched) {
                    metadataPage.releaseReadLatch();
                }
                bufferCache.unpin(metadataPage);
            }
        }

        return false; // Not found in any data page
    }

    private void handleDataPageOverflow(long metadataPageId, double[] vector, double distance, int centroidId,
            ITupleReference originalTuple, VTreeOpContext ctx, int fileId) throws HyracksDataException {
        // This method creates the FIRST data page of a directory page and therefore leaves the data-page
        // chain's next-page pointers alone: with no existing entry there is no predecessor to link from, and
        // the search cursor reaches data pages only by starting at directory entry 0 and following the chain.
        // Its single caller reaches it exactly when the directory page has no entries (a page with entries
        // always yields either a covering entry or the catch-all last entry). Enforce that here rather than
        // in prose: if this ever runs against a populated directory the new page would be registered in the
        // directory but unreachable from the chain, i.e. silently invisible to search.
        VTreeMetadataFrame directoryFrame = requireLatchedMetadataFrame(metadataPageId, ctx);
        if (directoryFrame.getTupleCount() != 0) {
            throw HyracksDataException.create(ErrorCode.ILLEGAL_STATE,
                    "handleDataPageOverflow on a non-empty directory page " + metadataPageId + " ("
                            + directoryFrame.getTupleCount()
                            + " entries): the new data page would not be linked into the data-page chain");
        }

        // Use the frame factories and page manager to handle overflow
        IVTreeDataFrame dataFrame = (IVTreeDataFrame) ctx.getDataFrameFactory().createFrame();
        IPageManager pageManager = ctx.getFreePageManager();

        // Create a new data page for overflow
        int newDataPageId = pageManager.takePage(ctx.getMetaFrame());
        ICachedPage newPage = bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, newDataPageId), NEW);

        boolean latched = false;
        try {
            newPage.acquireWriteLatch();
            latched = true;
            // Initialize the new data frame
            dataFrame.setPage(newPage);
            dataFrame.initBuffer((byte) 0);

            // Create data tuple for the new vector
            ITupleReference dataTuple =
                    ctx.getDataTupleBuilder().buildDataTuple(vector, distance, centroidId, originalTuple);

            // Insert the tuple into the new page
            dataFrame.insert(dataTuple, 0);

            // Update metadata page to include the new data page
            updateMetadataWithNewDataPage(metadataPageId, newDataPageId, distance, ctx, fileId);

        } finally {
            if (latched) {
                newPage.releaseWriteLatch(true);
            }
            bufferCache.unpin(newPage);
        }
    }

    /**
     * Update metadata maxDistance if the new distance exceeds the current maxDistance.
     * This is needed when inserting into the last data page with a distance greater than
     * the current maxDistance - the last page acts as a catch-all that dynamically expands.
     */
    private void updateMetadataMaxDistanceIfNeeded(long metadataPageId, long dataPageId, double newDistance,
            VTreeOpContext ctx) throws HyracksDataException {

        VTreeMetadataFrame metadataFrame = requireLatchedMetadataFrame(metadataPageId, ctx);

        // Find the metadata entry for this data page
        int tupleCount = metadataFrame.getTupleCount();
        for (int i = 0; i < tupleCount; i++) {
            long pagePtr = metadataFrame.getDataPagePointer(i);

            if (pagePtr == dataPageId) {
                double currentMaxDistance = metadataFrame.getMaxDistance(i);

                // Only update if new distance is larger
                if (newDistance > currentMaxDistance) {
                    metadataFrame.updateMaxDistance(i, newDistance);
                }
                break;
            }
        }
    }

    /**
     * Force update metadata maxDistance to a specific value (regardless of increase/decrease).
     * This is needed after data page splits where the original page's maxDistance decreases.
     */
    private void forceUpdateMetadataMaxDistance(long metadataPageId, long dataPageId, double newMaxDistance,
            VTreeOpContext ctx) throws HyracksDataException {

        VTreeMetadataFrame metadataFrame = requireLatchedMetadataFrame(metadataPageId, ctx);

        // Find the metadata entry for this data page
        int tupleCount = metadataFrame.getTupleCount();
        for (int i = 0; i < tupleCount; i++) {
            long pagePtr = metadataFrame.getDataPagePointer(i);

            if (pagePtr == dataPageId) {
                metadataFrame.updateMaxDistance(i, newMaxDistance);
                break;
            }
        }
    }

    /**
     * Update metadata page to include a new data page. Handles metadata page overflow by splitting when necessary.
     */
    private void updateMetadataWithNewDataPage(long metadataPageId, int newDataPageId, double maxDistance,
            VTreeOpContext ctx, int fileId) throws HyracksDataException {

        VTreeMetadataFrame metadataFrame = requireLatchedMetadataFrame(metadataPageId, ctx);

        // Create metadata tuple for new data page
        ITupleReference metadataTuple = VTreeMetadataTupleAccessor.createMetadataTuple(maxDistance, newDataPageId);
        ITreeIndexTupleWriter metadataFrameTupleWriter = metadataFrame.getTupleWriter();
        int slotSize = metadataFrame.getSlotSize();

        // Check if there's space for the new metadata entry
        // Check if directory page has space
        int spaceNeeded = metadataFrameTupleWriter.bytesRequired(metadataTuple) + slotSize;
        int spaceAvailable = metadataFrame.getTotalFreeSpace();

        if (spaceNeeded > spaceAvailable) {
            // Insufficient space - need to split metadata page
            handleMetadataPageOverflow(metadataTuple, ctx, fileId);
        } else {
            // Insert the new data-page entry in its sorted position by max_distance, preserving the
            // directory's max_distance-ascending invariant (see VTreeMetadataFrame javadoc) that
            // findDataPageInMetadataPage() relies on for correct distance-based routing. Appending at
            // getTupleCount() here corrupted that invariant after a non-last data-page split (the new
            // page's max_distance falls between existing entries), which mis-routed subsequent inserts,
            // produced overlapping data-page distance ranges, and broke the sorted-stream precondition
            // of the search-side merge and the matter/delete-marker reconciliation done in the LSM layer.
            int insertPos = metadataFrame.findInsertPosition(maxDistance);
            metadataFrame.insert(metadataTuple, insertPos);
        }
    }

    /**
     * Return the shared metadata frame that the caller already holds pinned and write-latched for
     * {@code metadataPageId}.
     * <p>
     * All metadata-mutation helpers ({@link #updateMetadataMaxDistanceIfNeeded},
     * {@link #forceUpdateMetadataMaxDistance}, {@link #updateMetadataWithNewDataPage}) run only from
     * inside {@link #insertIntoDataPages}, which pins the current directory page, write-latches it, sets
     * {@code ctx.getMetadataFrame()} to it, and releases the latch (marking the page dirty) in its own
     * {@code finally}. Operating on that already-latched frame here — instead of re-pinning and
     * re-latching the same page — removes redundant buffer-cache I/O and the former reliance on latch
     * reentrancy ({@code ReentrantReadWriteLock}) that a non-reentrant latch would have turned into a
     * self-deadlock. {@link #handleMetadataPageOverflow} already assumes this same shared-frame contract.
     */
    private VTreeMetadataFrame requireLatchedMetadataFrame(long metadataPageId, VTreeOpContext ctx) {
        assert metadataPageId == ctx.getMetadataPageId() : "metadata mutation on page " + metadataPageId
                + " but the write-latched page is " + ctx.getMetadataPageId();
        return (VTreeMetadataFrame) ctx.getMetadataFrame();
    }

    /**
     * Handle metadata page overflow by splitting the page and distributing tuples.
     */
    private void handleMetadataPageOverflow(ITupleReference newTuple, VTreeOpContext ctx, int fileId)
            throws HyracksDataException {

        // Allocate a new metadata page
        int newMetadataPageId = freePageManager.takePage(ctx.getMetaFrame());
        ICachedPage newMetadataPage = bufferCache.pin(BufferedFileHandle.getDiskPageId(fileId, newMetadataPageId), NEW);

        boolean latched = false;
        try {
            newMetadataPage.acquireWriteLatch();
            latched = true;

            // Create new metadata frame for the split page
            IVTreeMetadataFrame rightFrame = (IVTreeMetadataFrame) metadataFrameFactory.createFrame();
            rightFrame.setPage(newMetadataPage);
            rightFrame.initBuffer((byte) 0);

            // Capture the split page's successor BEFORE splitting: split() re-initializes both halves, which
            // resets their next-page pointers to the end-of-chain sentinel. The directory page being split is
            // not necessarily the last in its chain (this method runs on whichever page the insert walk landed
            // on), so terminating the right half unconditionally would orphan every page after it — making
            // their data pages unreachable to both the insert walk and tryPhysicalDelete. Splice the new page
            // in: left -> new -> original successor, mirroring splitDataPageMaintainOrder().
            int originalNextPage = ctx.getMetadataFrame().getNextPage();

            // Split the current metadata page using the correct method from VTreeMetadataFrame
            ((VTreeMetadataFrame) ctx.getMetadataFrame()).split(rightFrame, newTuple);

            // Update the next page pointer in the original metadata page
            ctx.getMetadataFrame().setNextPage(newMetadataPageId);

            // The new (right) page inherits the split page's successor, which is the end-of-chain sentinel
            // exactly when the split page was itself last in the chain.
            rightFrame.setNextPage(originalNextPage);

        } finally {
            if (latched) {
                newMetadataPage.releaseWriteLatch(true);
            }
            bufferCache.unpin(newMetadataPage);
        }
    }
}
