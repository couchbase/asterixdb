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
package org.apache.hyracks.storage.am.lsm.btree.column.api;

import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.apache.hyracks.storage.am.lsm.btree.column.impls.btree.ColumnBTreeReadLeafFrame;
import org.apache.hyracks.storage.am.lsm.btree.column.impls.btree.IColumnPageZeroWriterFlavorSelector;

/**
 * Provided for columnar read tuple reference
 */
public abstract class AbstractColumnTupleReader extends AbstractTupleWriterDisabledMethods {
    protected final IColumnPageZeroWriterFlavorSelector pageZeroWriterFlavorSelector;

    protected AbstractColumnTupleReader(IColumnPageZeroWriterFlavorSelector pageZeroWriterFlavorSelector) {
        this.pageZeroWriterFlavorSelector = pageZeroWriterFlavorSelector;
    }

    public abstract IColumnTupleIterator createTupleIterator(ColumnBTreeReadLeafFrame frame, int componentIndex,
            IColumnReadMultiPageOp multiPageOp);

    @Override
    public final int bytesRequired(ITupleReference tuple) {
        throw new UnsupportedOperationException(UNSUPPORTED_OPERATION_MSG);
    }

    public IColumnPageZeroWriterFlavorSelector getPageZeroWriterFlavorSelector() {
        return pageZeroWriterFlavorSelector;
    }
}
