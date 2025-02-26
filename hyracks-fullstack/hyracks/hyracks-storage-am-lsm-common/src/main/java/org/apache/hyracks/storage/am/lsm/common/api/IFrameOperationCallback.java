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

import java.io.Closeable;

import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * An interface that is used to enable frame level operation on indexes
 */
public interface IFrameOperationCallback extends Closeable {
    /**
     * Called once processing the frame is done before calling nextFrame on the next IFrameWriter in
     * the pipeline. In the event this frame completion will also exit the component, this will be
     * called prior to {@link #beforeExit(boolean)}.
     *
     * @throws HyracksDataException
     */
    void frameCompleted() throws HyracksDataException;

    /**
     * Called just prior to exiting the component on batch completion: not all batches may result
     * in a component exit, depending on the decision of the {@link IBatchController}.
     *
     * @throws HyracksDataException
     */
    void beforeExit(boolean success) throws HyracksDataException;

    /**
     * Called when the batch processing, {@link #frameCompleted()} or {@link #beforeExit(boolean)}
     * invocation has failed.
     *
     * @param th
     */
    void fail(Throwable th);

    /**
     * Called when the task has opened for initialization.
     */
    void open() throws HyracksDataException;
}
