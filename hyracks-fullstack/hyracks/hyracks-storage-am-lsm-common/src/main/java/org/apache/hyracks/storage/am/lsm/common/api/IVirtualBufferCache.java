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
package org.apache.hyracks.storage.am.lsm.common.api;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.common.buffercache.IBufferCache;
import org.apache.hyracks.storage.common.file.IFileMapManager;

public interface IVirtualBufferCache extends IBufferCache {
    void open() throws HyracksDataException;

    /**
     *
     * @return true if the overall memory usage exceeds the budget
     */
    boolean isFull();

    /**
     * @param memoryComponent
     * @return true if the memory component's memory usage exceeds its budget
     */
    boolean isFull(ILSMMemoryComponent memoryComponent);

    IFileMapManager getFileMapProvider();

    /**
    *
    * @return the number of in-use pages
    */
    int getUsage();

    /**
    * Register the memory component when it is allocated
    * @param memoryComponent
    */
    void register(ILSMMemoryComponent memoryComponent);

    /**
     * Unregister the memory component when it is deallocated
     * @param memoryComponent
     */
    void unregister(ILSMMemoryComponent memoryComponent);

    /**
     * Notify that virtual buffer cache that the memory component has been flushed to disk
     * @param memoryComponent
     * @throws HyracksDataException
     */
    void flushed(ILSMMemoryComponent memoryComponent) throws HyracksDataException;

    default String dumpState() {
        return "";
    }
}
