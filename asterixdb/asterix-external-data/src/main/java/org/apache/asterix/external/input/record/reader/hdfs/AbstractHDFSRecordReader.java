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
package org.apache.asterix.external.input.record.reader.hdfs;

import java.io.IOException;

import org.apache.asterix.external.api.IRawRecord;
import org.apache.asterix.external.api.IRecordReader;
import org.apache.asterix.external.dataflow.AbstractFeedDataFlowController;
import org.apache.asterix.external.input.record.GenericRecord;
import org.apache.asterix.external.util.IFeedLogManager;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.security.UserGroupInformation;

public abstract class AbstractHDFSRecordReader<K, V> implements IRecordReader<V> {
    protected RecordReader<K, V> reader;
    protected V value = null;
    protected K key = null;
    protected int currentSplitIndex = 0;
    protected boolean[] read;
    protected InputFormat<?, ?> inputFormat;
    protected InputSplit[] inputSplits;
    protected String[] readSchedule;
    protected String nodeName;
    protected JobConf conf;
    protected IRawRecord<V> record;
    private boolean firstInputSplit;
    protected UserGroupInformation ugi;

    public AbstractHDFSRecordReader(boolean[] read, InputSplit[] inputSplits, String[] readSchedule, String nodeName,
            JobConf conf, UserGroupInformation ugi) {
        this.ugi = ugi;
        this.read = read;
        this.inputSplits = inputSplits;
        this.readSchedule = readSchedule;
        this.nodeName = nodeName;
        this.conf = conf;
        this.inputFormat = conf.getInputFormat();
        this.record = new GenericRecord<>();
        reader = new EmptyRecordReader<>();
        firstInputSplit = false;
    }

    public AbstractHDFSRecordReader(boolean[] read, InputSplit[] inputSplits, String[] readSchedule, String nodeName,
            IRawRecord<V> record, JobConf conf, UserGroupInformation ugi) {
        this.ugi = ugi;
        this.read = read;
        this.inputSplits = inputSplits;
        this.readSchedule = readSchedule;
        this.nodeName = nodeName;
        this.conf = conf;
        this.inputFormat = conf.getInputFormat();
        this.record = record;
        reader = new EmptyRecordReader<>();
        firstInputSplit = false;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    @Override
    public boolean hasNext() throws Exception {
        if (!firstInputSplit) {
            firstInputSplit = true;
            nextInputSplit();
        }

        do {
            if (readerHasNext()) {
                return true;
            }
        } while (nextInputSplit());
        return false;
    }

    @Override
    public IRawRecord<V> next() throws IOException {
        record.set(value);
        return record;
    }

    protected boolean readerHasNext() throws IOException {
        return reader.next(key, value);
    }

    private boolean nextInputSplit() throws IOException {
        for (; currentSplitIndex < inputSplits.length; currentSplitIndex++) {
            /**
             * read all the partitions scheduled to the current node
             */
            if (readSchedule[currentSplitIndex].equals(nodeName)) {
                /**
                 * pick an unread split to read synchronize among
                 * simultaneous partitions in the same machine
                 */
                boolean skipToNextInputsplit = false;
                synchronized (read) {
                    if (!read[currentSplitIndex]) {
                        read[currentSplitIndex] = true;
                    } else {
                        skipToNextInputsplit = true;
                    }
                }

                skipToNextInputsplit |= onNextInputSplit();

                if (skipToNextInputsplit) {
                    continue;
                }

                closeRecordReader();
                setRecordReader(currentSplitIndex);
                return true;
            }
        }
        return false;
    }

    protected void closeRecordReader() throws IOException {
        reader.close();
    }

    /**
     * Returns true if need to go to next split without closing the current reader
     *
     * @throws IOException
     */
    protected abstract boolean onNextInputSplit() throws IOException;

    protected abstract void setRecordReader(int splitIndex) throws IOException;

    @Override
    public boolean stop() {
        return false;
    }

    public RecordReader<K, V> getReader() {
        return reader;
    }

    @Override
    public void setFeedLogManager(IFeedLogManager feedLogManager) {
    }

    @Override
    public void setController(AbstractFeedDataFlowController controller) {
    }

    @Override
    public boolean handleException(Throwable th) {
        return false;
    }
}
