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
package org.apache.hyracks.control.nc.io;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.commons.io.FileUtils;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.io.IDiskSpaceMaker;
import org.apache.hyracks.api.io.IFileDeviceResolver;
import org.apache.hyracks.api.io.IFileHandle;
import org.apache.hyracks.api.io.IIOBulkOperation;
import org.apache.hyracks.api.io.IIOManager;
import org.apache.hyracks.api.io.IODeviceHandle;
import org.apache.hyracks.api.util.InvokeUtil;
import org.apache.hyracks.api.util.IoUtil;
import org.apache.hyracks.control.nc.io.IoRequest.State;
import org.apache.hyracks.control.nc.io.bulk.AbstractBulkOperation;
import org.apache.hyracks.control.nc.io.bulk.DeleteBulkOperation;
import org.apache.hyracks.util.file.FileUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class IOManager implements IIOManager {
    /*
     * Constants
     */
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String WORKSPACE_FILE_SUFFIX = ".waf";
    private static final String STORAGE_ROOT_DIR_NAME = "storage";
    private static final FilenameFilter WORKSPACE_FILES_FILTER = (dir, name) -> name.endsWith(WORKSPACE_FILE_SUFFIX);
    /*
     * Finals
     */
    private final int queueSize;
    private final int ioParallelism;
    private final ExecutorService executor;
    private final BlockingQueue<IoRequest> submittedRequests;
    private final BlockingQueue<IoRequest> freeRequests;
    protected final List<IODeviceHandle> ioDevices;
    private final List<IODeviceHandle> workspaces;
    private final IFileDeviceResolver deviceComputer;
    /*
     * Mutables
     */
    private int workspaceIndex;
    // TODO use space make on write
    private IDiskSpaceMaker spaceMaker;

    public IOManager(List<IODeviceHandle> devices, IFileDeviceResolver deviceComputer, int ioParallelism, int queueSize)
            throws HyracksDataException {
        this.ioDevices = Collections.unmodifiableList(devices);
        this.queueSize = queueSize;
        this.ioParallelism = ioParallelism;
        checkDeviceValidity(devices);
        workspaces = new ArrayList<>();
        for (IODeviceHandle d : ioDevices) {
            if (d.getWorkspace() != null) {
                try {
                    FileUtil.forceMkdirs(getWorkspaceFolder(d));
                } catch (IOException e) {
                    throw HyracksDataException.create(e);
                }
                workspaces.add(d);
            }
        }
        if (workspaces.isEmpty()) {
            throw new HyracksDataException("No devices with workspace found");
        }
        workspaceIndex = 0;
        this.deviceComputer = deviceComputer;
        submittedRequests = new ArrayBlockingQueue<>(this.queueSize);
        freeRequests = new ArrayBlockingQueue<>(this.queueSize);
        int numIoThreads = ioDevices.size() * this.ioParallelism;
        executor = Executors.newFixedThreadPool(numIoThreads);
        for (int i = 0; i < numIoThreads; i++) {
            executor.execute(new IoRequestHandler(i, submittedRequests));
        }
        spaceMaker = NoOpDiskSpaceMaker.INSTANCE;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getIOParallelism() {
        return ioParallelism;
    }

    public IFileDeviceResolver getDeviceComputer() {
        return deviceComputer;
    }

    public IoRequest getOrAllocRequest() {
        IoRequest request = freeRequests.poll();
        if (request == null) {
            request = new IoRequest(this, submittedRequests, freeRequests);
        }
        return request;
    }

    private void checkDeviceValidity(List<IODeviceHandle> devices) throws HyracksDataException {
        for (IODeviceHandle d : devices) {
            Path p = Paths.get(d.getMount().toURI());
            for (IODeviceHandle e : devices) {
                if (e != d) {
                    Path q = Paths.get(e.getMount().toURI());
                    if (p.equals(q)) {
                        throw HyracksDataException.create(ErrorCode.DUPLICATE_IODEVICE);
                    } else if (p.startsWith(q)) {
                        throw HyracksDataException.create(ErrorCode.NESTED_IODEVICES);
                    }
                }
            }

        }
    }

    @Override
    public List<IODeviceHandle> getIODevices() {
        return ioDevices;
    }

    @Override
    public IFileHandle open(FileReference fileRef, FileReadWriteMode rwMode, FileSyncMode syncMode)
            throws HyracksDataException {
        FileHandle fHandle = new FileHandle(fileRef);
        try {
            fHandle.open(rwMode, syncMode);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
        return fHandle;
    }

    @Override
    public int syncWrite(IFileHandle fHandle, long offset, ByteBuffer data) throws HyracksDataException {
        IoRequest req = asyncWrite(fHandle, offset, data);
        InvokeUtil.doUninterruptibly(req);
        try {
            if (req.getState() == State.OPERATION_SUCCEEDED) {
                return req.getWrite();
            } else if (req.getState() == State.OPERATION_FAILED) {
                throw req.getFailure();
            } else {
                throw new IllegalStateException("Write request completed with state " + req.getState());
            }
        } finally {
            req.recycle();
        }
    }

    @Override
    public long syncWrite(IFileHandle fHandle, long offset, ByteBuffer[] dataArray) throws HyracksDataException {
        IoRequest req = asyncWrite(fHandle, offset, dataArray);
        InvokeUtil.doUninterruptibly(req);
        try {
            if (req.getState() == State.OPERATION_SUCCEEDED) {
                return req.getWrites();
            } else if (req.getState() == State.OPERATION_FAILED) {
                throw req.getFailure();
            } else {
                throw new IllegalStateException("Write request completed with state " + req.getState());
            }
        } finally {
            req.recycle();
        }
    }

    public int doSyncWrite(IFileHandle fHandle, long offset, ByteBuffer data) throws HyracksDataException {
        try {
            if (fHandle == null) {
                throw new IllegalStateException("Trying to write to a deleted file.");
            }
            int n = 0;
            int remaining = data.remaining();
            while (remaining > 0) {
                int len = ((FileHandle) fHandle).getFileChannel().write(data, offset);
                if (len < 0) {
                    throw new HyracksDataException("Error writing to file: " + fHandle.getFileReference().toString());
                }
                remaining -= len;
                offset += len;
                n += len;
            }
            return n;
        } catch (HyracksDataException e) {
            throw e;
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    public long doSyncWrite(IFileHandle fHandle, long offset, ByteBuffer[] dataArray) throws HyracksDataException {
        try {
            if (fHandle == null) {
                throw new IllegalStateException("Trying to write to a deleted file.");
            }
            int n = 0;
            int remaining = 0;
            for (ByteBuffer buf : dataArray) {
                remaining += buf.remaining();
            }
            final FileChannel fileChannel = ((FileHandle) fHandle).getFileChannel();
            while (remaining > 0) {
                long len;
                synchronized (fileChannel) {
                    fileChannel.position(offset);
                    len = fileChannel.write(dataArray);
                }
                if (len < 0) {
                    throw new HyracksDataException("Error writing to file: " + fHandle.getFileReference().toString());
                }
                remaining -= len;
                offset += len;
                n += len;
            }
            return n;
        } catch (HyracksDataException e) {
            throw e;
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    /**
     * Please do check the return value of this read!
     *
     * @param fHandle
     * @param offset
     * @param data
     * @return The number of bytes read, possibly zero, or -1 if the given offset is greater than or equal to the file's
     * current size
     * @throws HyracksDataException
     */
    @Override
    public int syncRead(IFileHandle fHandle, long offset, ByteBuffer data) throws HyracksDataException {
        IoRequest req = asyncRead(fHandle, offset, data);
        InvokeUtil.doUninterruptibly(req);
        try {
            if (req.getState() == State.OPERATION_SUCCEEDED) {
                return req.getRead();
            } else if (req.getState() == State.OPERATION_FAILED) {
                throw req.getFailure();
            } else {
                throw new IllegalStateException("Reqd request completed with state " + req.getState());
            }
        } finally {
            req.recycle();
        }
    }

    public int doSyncRead(IFileHandle fHandle, long offset, ByteBuffer data) throws HyracksDataException {
        try {
            int n = 0;
            int remaining = data.remaining();
            while (remaining > 0) {
                int len = ((FileHandle) fHandle).getFileChannel().read(data, offset);
                if (len < 0) {
                    return n == 0 ? -1 : n;
                }
                remaining -= len;
                offset += len;
                n += len;
            }
            return n;
        } catch (ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            // re-open the closed channel. The channel will be closed during the typical file lifecycle
            ((FileHandle) fHandle).ensureOpen();
            throw HyracksDataException.create(e);
        } catch (ClosedChannelException e) {
            throw HyracksDataException.create(ErrorCode.CANNOT_READ_CLOSED_FILE, e, fHandle.getFileReference());
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public IoRequest asyncWrite(IFileHandle fHandle, long offset, ByteBuffer[] dataArray) throws HyracksDataException {
        IoRequest req = getOrAllocRequest();
        try {
            req.write(fHandle, offset, dataArray);
        } catch (HyracksDataException e) {
            req.recycle();
            throw e;
        }
        return req;
    }

    @Override
    public IoRequest asyncWrite(IFileHandle fHandle, long offset, ByteBuffer data) throws HyracksDataException {
        IoRequest req = getOrAllocRequest();
        try {
            req.write(fHandle, offset, data);
        } catch (HyracksDataException e) {
            req.recycle();
            throw e;
        }
        return req;
    }

    @Override
    public IoRequest asyncRead(IFileHandle fHandle, long offset, ByteBuffer data) throws HyracksDataException {
        IoRequest req = getOrAllocRequest();
        try {
            req.read(fHandle, offset, data);
        } catch (HyracksDataException e) {
            req.recycle();
            throw e;
        }
        return req;
    }

    @Override
    public void close(IFileHandle fHandle) throws HyracksDataException {
        try {
            ((FileHandle) fHandle).close();
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public synchronized FileReference createWorkspaceFile(String prefix) throws HyracksDataException {
        IODeviceHandle dev = workspaces.get(workspaceIndex);
        workspaceIndex = (workspaceIndex + 1) % workspaces.size();
        String waPath = dev.getWorkspace();
        File waf;
        try {
            waf = File.createTempFile(prefix, WORKSPACE_FILE_SUFFIX, new File(dev.getMount(), waPath));
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
        return dev.createFileRef(waPath + File.separator + waf.getName());
    }

    public File getWorkspacePath(int index) {
        IODeviceHandle dev = workspaces.get(index);
        return dev != null ? getWorkspaceFolder(dev) : null;
    }

    private File getWorkspaceFolder(IODeviceHandle dev) {
        return Path.of(dev.getMount().getPath(), dev.getWorkspace()).normalize().toFile();
    }

    @Override
    public void sync(IFileHandle fileHandle, boolean metadata) throws HyracksDataException {
        try {
            ((FileHandle) fileHandle).getFileChannel().force(metadata);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public void truncate(IFileHandle fileHandle, long size) throws HyracksDataException {
        try {
            ((FileHandle) fileHandle).getFileChannel().truncate(size);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public long getSize(IFileHandle fileHandle) throws HyracksDataException {
        return getSize(fileHandle.getFileReference());
    }

    @Override
    public long getSize(FileReference fileReference) throws HyracksDataException {
        return fileReference.getFile().length();
    }

    @Override
    public void deleteWorkspaceFiles() throws HyracksDataException {
        for (IODeviceHandle ioDevice : workspaces) {
            File workspaceFolder = getWorkspaceFolder(ioDevice);
            if (workspaceFolder.exists() && workspaceFolder.isDirectory()) {
                File[] workspaceFiles = workspaceFolder.listFiles(WORKSPACE_FILES_FILTER);
                for (File workspaceFile : workspaceFiles) {
                    IoUtil.delete(workspaceFile);
                }
            }
        }
    }

    @Override
    public synchronized FileReference getFileReference(int ioDeviceId, String relativePath) {
        IODeviceHandle devHandle = ioDevices.get(ioDeviceId);
        return new FileReference(devHandle, relativePath);
    }

    @Override
    public FileReference resolve(String path) throws HyracksDataException {
        return new FileReference(deviceComputer.resolve(path, getIODevices()), path);
    }

    @Override
    public FileReference resolveAbsolutePath(String path) throws HyracksDataException {
        IODeviceHandle devHandle = getDevice(path);
        if (devHandle == null) {
            throw HyracksDataException.create(ErrorCode.FILE_WITH_ABSOULTE_PATH_NOT_WITHIN_ANY_IO_DEVICE, path);
        }
        String relativePath = devHandle.getRelativePath(path);
        return new FileReference(devHandle, relativePath);
    }

    public IODeviceHandle getDevice(String fullPath) {
        Path full = Paths.get(fullPath).normalize();
        for (IODeviceHandle d : ioDevices) {
            if (full.startsWith(Paths.get(d.getMount().getAbsolutePath()).normalize())) {
                return d;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        InvokeUtil.doUninterruptibly(() -> submittedRequests.put(IoRequestHandler.POISON_PILL));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.log(Level.WARN, "Failure shutting down {} executor service", getClass().getSimpleName());
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARN, "Interrupted while shutting down {} executor service", getClass().getSimpleName());
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public long getTotalDiskUsage() {
        long totalSize = 0;
        for (IODeviceHandle handle : ioDevices) {
            totalSize += IoUtil.sizeOfDirectory(handle.getMount().toPath());
        }
        return totalSize;
    }

    @Override
    public WritableByteChannel newWritableChannel(IFileHandle fHandle) {
        FileHandle fh = (FileHandle) fHandle;
        if (!fh.isOpen()) {
            throw new IllegalStateException("closed");
        }
        return new WritableByteChannel() {
            long position;

            @Override
            public int write(ByteBuffer src) throws IOException {
                int origPos = src.position();
                int written = IOManager.this.syncWrite(fHandle, position, src);
                position += written;
                if (src.position() < origPos + written) {
                    src.position(origPos + written);
                }
                return written;
            }

            @Override
            public boolean isOpen() {
                return fh.isOpen();
            }

            @Override
            public void close() throws IOException {
                IOManager.this.close(fh);
            }
        };
    }

    @Override
    public void delete(FileReference fileRef) throws HyracksDataException {
        if (fileRef.getFile().exists()) {
            IoUtil.delete(fileRef);
        }
    }

    @Override
    public IIOBulkOperation createDeleteBulkOperation() {
        return new DeleteBulkOperation(this);
    }

    @Override
    public Set<FileReference> list(FileReference dir) throws HyracksDataException {
        return list(dir, IoUtil.NO_OP_FILTER);
    }

    @Override
    public Set<FileReference> list(FileReference dir, FilenameFilter filter) throws HyracksDataException {
        Set<FileReference> listedFiles = new HashSet<>();
        if (!dir.getFile().exists()) {
            return listedFiles;
        }
        Collection<File> files = IoUtil.getMatchingFiles(dir.getFile().toPath(), filter);
        for (File file : files) {
            listedFiles.add(resolveAbsolutePath(file.getAbsolutePath()));
        }
        return listedFiles;
    }

    @Override
    public void overwrite(FileReference fileRef, byte[] bytes) throws HyracksDataException {
        File file = fileRef.getFile();
        try {
            if (file.exists()) {
                delete(fileRef);
            } else {
                file.getParentFile().mkdirs();
            }
            FileUtil.writeAndForce(file.toPath(), bytes);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public byte[] readAllBytes(FileReference fileRef) throws HyracksDataException {
        if (!fileRef.getFile().exists()) {
            return null;
        }
        try {
            return Files.readAllBytes(fileRef.getFile().toPath());
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public boolean exists(FileReference fileRef) throws HyracksDataException {
        return fileRef.getFile().exists();
    }

    @Override
    public void create(FileReference fileRef) throws HyracksDataException {
        IoUtil.create(fileRef);
    }

    @Override
    public boolean makeDirectories(FileReference resourceDir) {
        return resourceDir.getFile().mkdirs();
    }

    @Override
    public void cleanDirectory(FileReference resourceDir) throws HyracksDataException {
        try {
            FileUtils.cleanDirectory(resourceDir.getFile());
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public void copyDirectory(FileReference srcFileRef, FileReference destFileRef) throws HyracksDataException {
        try {
            FileUtils.copyDirectory(srcFileRef.getFile(), destFileRef.getFile());
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    @Override
    public void performBulkOperation(IIOBulkOperation bulkOperation) throws HyracksDataException {
        ((AbstractBulkOperation) bulkOperation).performOperation();
    }

    @Override
    public long getSize(Predicate<String> relativePathFilter) {
        long totalSize = 0;

        // get cached files (read from disk)
        for (IODeviceHandle deviceHandle : getIODevices()) {
            FileReference storageRoot = deviceHandle.createFileRef(STORAGE_ROOT_DIR_NAME);

            Set<FileReference> deviceFiles;
            try {
                deviceFiles = list(storageRoot, IoUtil.NO_OP_FILTER);
            } catch (Throwable th) {
                LOGGER.info("Failed to get local storage files for root {}", storageRoot.getRelativePath(), th);
                continue;
            }

            for (FileReference fileReference : deviceFiles) {
                try {
                    if (relativePathFilter.test(fileReference.getRelativePath())) {
                        totalSize += fileReference.getFile().length();
                    }
                } catch (Throwable th) {
                    LOGGER.info("Encountered issue for local storage file {}", fileReference.getRelativePath(), th);
                }
            }
        }

        return totalSize;
    }

    public void setSpaceMaker(IDiskSpaceMaker spaceMaker) {
        this.spaceMaker = spaceMaker;
    }
}
