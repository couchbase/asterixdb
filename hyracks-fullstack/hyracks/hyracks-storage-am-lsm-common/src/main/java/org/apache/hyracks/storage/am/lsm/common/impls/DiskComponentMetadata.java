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
package org.apache.hyracks.storage.am.lsm.common.impls;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IValueReference;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.storage.am.common.api.IMetadataPageManager;
import org.apache.hyracks.storage.am.lsm.common.api.IComponentMetadata;

public class DiskComponentMetadata implements IComponentMetadata {

    private final IMetadataPageManager mdpManager;

    public DiskComponentMetadata(IMetadataPageManager mdpManager) {
        this.mdpManager = mdpManager;
    }

    @Override
    public int getPageSize() {
        return mdpManager.getPageSize();
    }

    @Override
    public int getAvailableSpace() throws HyracksDataException {
        return mdpManager.getFreeSpace();
    }

    @Override
    public void put(IValueReference key, IValueReference value) throws HyracksDataException {
        mdpManager.put(mdpManager.createMetadataFrame(), key, value);
    }

    @Override
    public boolean get(IValueReference key, ArrayBackedValueStorage storage) throws HyracksDataException {
        return mdpManager.get(mdpManager.createMetadataFrame(), key, storage);
    }

    public void put(MemoryComponentMetadata metadata) throws HyracksDataException {
        metadata.copy(mdpManager);
    }

    public IMetadataPageManager getMetadataPageManager() {
        return mdpManager;
    }
}
