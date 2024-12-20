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
package org.apache.hyracks.algebricks.runtime.operators.writer;

import java.io.Serializable;

import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;

public class WriterPartitionerFactory implements Serializable {
    private static final long serialVersionUID = 8971234908711239L;
    private final boolean partitionByKey;
    private final int[] partitionColumns;
    private final IBinaryComparatorFactory[] partitionComparatorFactories;

    public WriterPartitionerFactory(int[] partitionColumns, IBinaryComparatorFactory[] partitionComparatorFactories) {
        this(partitionColumns, partitionComparatorFactories, false);
    }

    public WriterPartitionerFactory() {
        this(null, null, true);
    }

    private WriterPartitionerFactory(int[] partitionColumns, IBinaryComparatorFactory[] partitionComparatorFactories,
            boolean partitionByKey) {
        this.partitionColumns = partitionColumns;
        this.partitionComparatorFactories = partitionComparatorFactories;
        this.partitionByKey = partitionByKey;
    }

    /**
     * Creates the writer partitioner based on the provided parameters
     *
     * @return writer partitioner
     */
    public IWriterPartitioner createPartitioner() {
        // key writer partitioner
        if (partitionByKey) {
            return KeyWriterPartitioner.INSTANCE;
        }

        // writer partitioner
        if (partitionColumns.length > 0) {
            IBinaryComparator[] comparators = new IBinaryComparator[partitionComparatorFactories.length];
            for (int i = 0; i < partitionComparatorFactories.length; i++) {
                comparators[i] = partitionComparatorFactories[i].createBinaryComparator();
            }

            return new WriterPartitioner(partitionColumns, comparators);
        }

        // no-op partitioner
        return new NoOpWriterPartitioner();
    }
}
