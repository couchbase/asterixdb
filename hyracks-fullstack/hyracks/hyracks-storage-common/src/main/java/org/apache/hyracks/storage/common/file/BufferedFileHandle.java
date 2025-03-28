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
package org.apache.hyracks.storage.common.file;

import static org.apache.hyracks.storage.common.buffercache.BufferCache.DEBUG;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.io.IFileHandle;
import org.apache.hyracks.api.io.IIOManager;
import org.apache.hyracks.storage.common.buffercache.AbstractBufferedFileIOManager;
import org.apache.hyracks.storage.common.buffercache.BufferCache;
import org.apache.hyracks.storage.common.buffercache.BufferCacheHeaderHelper;
import org.apache.hyracks.storage.common.buffercache.CachedPage;
import org.apache.hyracks.storage.common.buffercache.IPageReplacementStrategy;
import org.apache.hyracks.storage.common.buffercache.context.IBufferCacheReadContext;
import org.apache.hyracks.storage.common.buffercache.context.IBufferCacheWriteContext;
import org.apache.hyracks.storage.common.compression.file.CompressedFileReference;
import org.apache.hyracks.storage.common.compression.file.ICompressedPageWriter;
import org.apache.hyracks.storage.common.compression.file.NoOpLAFWriter;
import org.apache.hyracks.util.IThreadStats;

public class BufferedFileHandle extends AbstractBufferedFileIOManager {
    private final int fileId;
    private final AtomicInteger refCount;

    protected BufferedFileHandle(int fileId, BufferCache bufferCache, IIOManager ioManager,
            BlockingQueue<BufferCacheHeaderHelper> headerPageCache, IPageReplacementStrategy pageReplacementStrategy) {
        super(bufferCache, ioManager, headerPageCache, pageReplacementStrategy);
        this.fileId = fileId;
        refCount = new AtomicInteger();
    }

    public int getFileId() {
        return fileId;
    }

    public int incReferenceCount() {
        return refCount.incrementAndGet();
    }

    public int decReferenceCount() {
        return refCount.decrementAndGet();
    }

    public int getReferenceCount() {
        return refCount.get();
    }

    public long getDiskPageId(int pageId) {
        return getDiskPageId(fileId, pageId);
    }

    @Override
    public void read(CachedPage cPage, IBufferCacheReadContext context, IThreadStats threadStats)
            throws HyracksDataException {
        final BufferCacheHeaderHelper header = checkoutHeaderHelper();
        try {
            setPageInfo(cPage);
            IFileHandle handle = getFileHandle();
            int pageSize = bufferCache.getPageSizeWithHeader();
            long bytesRead = header.readFromFile(ioManager, handle, getFirstPageOffset(cPage), pageSize);

            if (!verifyBytesRead(bufferCache.getPageSizeWithHeader(), bytesRead)) {
                return;
            }

            final ByteBuffer buf = context.processHeader(ioManager, this, header, cPage, threadStats);
            cPage.getBuffer().put(buf);
        } finally {
            returnHeaderHelper(header);
        }

        readExtraPages(cPage);
    }

    private void setPageInfo(CachedPage cPage) {
        cPage.setCompressedPageOffset(getFirstPageOffset(cPage));
        cPage.setCompressedPageSize(bufferCache.getPageSize());
    }

    private void readExtraPages(CachedPage cPage) throws HyracksDataException {
        final int totalPages = cPage.getFrameSizeMultiplier();
        if (totalPages > 1) {
            pageReplacementStrategy.fixupCapacityOnLargeRead(cPage);
            cPage.getBuffer().position(bufferCache.getPageSize());
            cPage.getBuffer().limit(totalPages * bufferCache.getPageSize());
            readToBuffer(cPage.getBuffer(), getExtraPageOffset(cPage));
        }
    }

    @Override
    protected void write(CachedPage cPage, BufferCacheHeaderHelper header, int totalPages, int extraBlockPageId,
            IBufferCacheWriteContext context) throws HyracksDataException {
        final ByteBuffer buf = cPage.getBuffer();
        final boolean contiguousLargePages = getPageId(cPage.getDiskPageId()) + 1 == extraBlockPageId;
        IFileHandle handle = getFileHandle();
        long bytesWritten;
        long offset;
        try {
            buf.limit(contiguousLargePages ? bufferCache.getPageSize() * totalPages : bufferCache.getPageSize());
            buf.position(0);
            ByteBuffer[] buffers = header.prepareWrite(cPage);
            offset = getFirstPageOffset(cPage);
            bytesWritten = context.write(ioManager, handle, offset, buffers);
        } finally {
            returnHeaderHelper(header);
        }

        if (totalPages > 1 && !contiguousLargePages) {
            buf.limit(totalPages * bufferCache.getPageSize());
            bytesWritten += writeExtraToFile(buf, getExtraPageOffset(cPage));
        }

        final int expectedWritten = bufferCache.getPageSizeWithHeader() + bufferCache.getPageSize() * (totalPages - 1);
        verifyBytesWritten(expectedWritten, bytesWritten);

        cPage.setCompressedPageOffset(offset);
        cPage.setCompressedPageSize((int) bytesWritten);
    }

    @Override
    public long getStartPageOffset(int pageId) throws HyracksDataException {
        return (long) pageId * bufferCache.getPageSizeWithHeader();
    }

    @Override
    public int getNumberOfPages() throws HyracksDataException {
        if (DEBUG) {
            assert getFileSize() % bufferCache.getPageSizeWithHeader() == 0;
        }
        return (int) (getFileSize() / bufferCache.getPageSizeWithHeader());
    }

    @Override
    public ICompressedPageWriter getCompressedPageWriter() {
        return NoOpLAFWriter.INSTACNE;
    }

    @Override
    public long getPagesTotalSize(int startPageId, int numberOfPages) throws HyracksDataException {
        // This could be an overestimate as we cannot determine for sure as extra pages do not have a header
        return (long) numberOfPages * bufferCache.getPageSizeWithHeader();
    }

    @Override
    protected long getFirstPageOffset(CachedPage cPage) {
        return getPageOffset(getPageId(cPage.getDiskPageId()));
    }

    @Override
    protected long getExtraPageOffset(CachedPage cPage) {
        return getPageOffset(cPage.getExtraBlockPageId());
    }

    public static long getDiskPageId(int fileId, int pageId) {
        return (((long) fileId) << 32) + pageId;
    }

    public static int getFileId(long dpid) {
        return (int) (dpid >> 32);
    }

    public static int getPageId(long dpid) {
        return (int) dpid;
    }

    public static BufferedFileHandle create(FileReference fileRef, int fileId, BufferCache bufferCache,
            IIOManager ioManager, BlockingQueue<BufferCacheHeaderHelper> headerPageCache,
            IPageReplacementStrategy pageReplacementStrategy) {
        if (fileRef.isCompressed()) {
            final CompressedFileReference cFileRef = (CompressedFileReference) fileRef;
            return new CompressedBufferedFileHandle(fileId, cFileRef.getLAFFileReference(), bufferCache, ioManager,
                    headerPageCache, pageReplacementStrategy);
        }
        return new BufferedFileHandle(fileId, bufferCache, ioManager, headerPageCache, pageReplacementStrategy);
    }

    private long getPageOffset(int pageId) {
        return (long) pageId * bufferCache.getPageSizeWithHeader();
    }
}
