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
package org.apache.asterix.metadata.entitytupletranslators;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.asterix.common.config.DatasetConfig.DatasetType;
import org.apache.asterix.common.metadata.DataverseName;
import org.apache.asterix.common.metadata.MetadataUtil;
import org.apache.asterix.metadata.bootstrap.DatasetEntity;
import org.apache.asterix.metadata.dataset.DatasetFormatInfo;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.entities.InternalDatasetDetails;
import org.apache.asterix.metadata.entities.InternalDatasetDetails.FileStructure;
import org.apache.asterix.metadata.entities.InternalDatasetDetails.PartitioningStrategy;
import org.apache.asterix.metadata.utils.Creator;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.runtime.compression.CompressionManager;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;
import org.junit.Assert;
import org.junit.Test;

public class DatasetTupleTranslatorTest {

    @Test
    public void test() throws AlgebricksException, IOException {
        Integer[] indicators = { 0, 1, null };
        for (Integer indicator : indicators) {
            Map<String, String> compactionPolicyProperties = new HashMap<>();
            compactionPolicyProperties.put("max-mergable-component-size", "1073741824");
            compactionPolicyProperties.put("max-tolerance-component-count", "3");

            InternalDatasetDetails details = new InternalDatasetDetails(FileStructure.BTREE, PartitioningStrategy.HASH,
                    Collections.singletonList(Collections.singletonList("row_id")),
                    Collections.singletonList(Collections.singletonList("row_id")),
                    indicator == null ? null : Collections.singletonList(indicator),
                    Collections.singletonList(BuiltinType.AINT64), false, null, null);

            DataverseName dv = DataverseName.createSinglePartName("test");
            DataverseName itemTypeDv = DataverseName.createSinglePartName("foo");
            DataverseName metaTypeDv = DataverseName.createSinglePartName("CB");
            String db = MetadataUtil.databaseFor(dv);
            String itemTypeDb = MetadataUtil.databaseFor(itemTypeDv);
            String metaTypeDb = MetadataUtil.databaseFor(metaTypeDv);
            Dataset dataset = new Dataset(db, dv, "log", itemTypeDb, itemTypeDv, "LogType", metaTypeDb, metaTypeDv,
                    "MetaType", "DEFAULT_NG_ALL_NODES", "prefix", compactionPolicyProperties, details,
                    Collections.emptyMap(), DatasetType.INTERNAL, 115, 0, CompressionManager.NONE,
                    DatasetFormatInfo.SYSTEM_DEFAULT, Creator.DEFAULT_CREATOR);
            DatasetTupleTranslator dtTranslator = new DatasetTupleTranslator(true, DatasetEntity.of(false));
            ITupleReference tuple = dtTranslator.getTupleFromMetadataEntity(dataset);
            Dataset deserializedDataset = dtTranslator.getMetadataEntityFromTuple(tuple);
            Assert.assertEquals(dataset.getMetaItemTypeDataverseName(),
                    deserializedDataset.getMetaItemTypeDataverseName());
            Assert.assertEquals(dataset.getMetaItemTypeName(), deserializedDataset.getMetaItemTypeName());
            if (indicator == null) {
                Assert.assertEquals(Collections.singletonList(Integer.valueOf(0)),
                        ((InternalDatasetDetails) deserializedDataset.getDatasetDetails()).getKeySourceIndicator());
            } else {
                Assert.assertEquals(((InternalDatasetDetails) dataset.getDatasetDetails()).getKeySourceIndicator(),
                        ((InternalDatasetDetails) deserializedDataset.getDatasetDetails()).getKeySourceIndicator());
            }
        }
    }
}
