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

import java.io.DataOutput;
import java.util.HashMap;
import java.util.Map;

import org.apache.asterix.builders.IARecordBuilder;
import org.apache.asterix.builders.RecordBuilder;
import org.apache.asterix.builders.UnorderedListBuilder;
import org.apache.asterix.common.metadata.DataverseName;
import org.apache.asterix.common.metadata.MetadataUtil;
import org.apache.asterix.metadata.bootstrap.MetadataRecordTypes;
import org.apache.asterix.metadata.entities.FeedPolicyEntity;
import org.apache.asterix.om.base.AMutableString;
import org.apache.asterix.om.base.ARecord;
import org.apache.asterix.om.base.AString;
import org.apache.asterix.om.base.AUnorderedList;
import org.apache.asterix.om.base.IACursor;
import org.apache.asterix.om.types.AUnorderedListType;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.data.accessors.ITupleReference;

/**
 * Translates a FeedPolicyEntity metadata entity to an ITupleReference and vice versa.
 */
public class FeedPolicyTupleTranslator extends AbstractTupleTranslator<FeedPolicyEntity> {

    private final org.apache.asterix.metadata.bootstrap.FeedPolicyEntity feedPolicyEntity;

    protected FeedPolicyTupleTranslator(boolean getTuple,
            org.apache.asterix.metadata.bootstrap.FeedPolicyEntity feedPolicyEntity) {
        super(getTuple, feedPolicyEntity.getIndex(), feedPolicyEntity.payloadPosition());
        this.feedPolicyEntity = feedPolicyEntity;
    }

    @Override
    protected FeedPolicyEntity createMetadataEntityFromARecord(ARecord feedPolicyRecord) throws AlgebricksException {
        String dataverseCanonicalName =
                ((AString) feedPolicyRecord.getValueByPos(feedPolicyEntity.dataverseNameIndex())).getStringValue();
        DataverseName dataverseName = DataverseName.createFromCanonicalForm(dataverseCanonicalName);
        int databaseNameIndex = feedPolicyEntity.databaseNameIndex();
        String databaseName;
        if (databaseNameIndex >= 0) {
            databaseName = ((AString) feedPolicyRecord.getValueByPos(databaseNameIndex)).getStringValue();
        } else {
            databaseName = MetadataUtil.databaseFor(dataverseName);
        }
        String policyName =
                ((AString) feedPolicyRecord.getValueByPos(feedPolicyEntity.policyNameIndex())).getStringValue();
        String description =
                ((AString) feedPolicyRecord.getValueByPos(feedPolicyEntity.descriptionIndex())).getStringValue();

        IACursor cursor =
                ((AUnorderedList) feedPolicyRecord.getValueByPos(feedPolicyEntity.propertiesIndex())).getCursor();
        Map<String, String> policyParamters = new HashMap<>();
        while (cursor.next()) {
            ARecord field = (ARecord) cursor.get();
            String key =
                    ((AString) field.getValueByPos(MetadataRecordTypes.PROPERTIES_NAME_FIELD_INDEX)).getStringValue();
            String value =
                    ((AString) field.getValueByPos(MetadataRecordTypes.PROPERTIES_VALUE_FIELD_INDEX)).getStringValue();
            policyParamters.put(key, value);
        }

        return new FeedPolicyEntity(databaseName, dataverseName, policyName, description, policyParamters);
    }

    @Override
    public ITupleReference getTupleFromMetadataEntity(FeedPolicyEntity feedPolicy) throws HyracksDataException {
        String dataverseCanonicalName = feedPolicy.getDataverseName().getCanonicalForm();

        // write the key in the first three fields of the tuple
        tupleBuilder.reset();
        if (feedPolicyEntity.databaseNameIndex() >= 0) {
            aString.setValue(feedPolicy.getDatabaseName());
            stringSerde.serialize(aString, tupleBuilder.getDataOutput());
            tupleBuilder.addFieldEndOffset();
        }
        aString.setValue(dataverseCanonicalName);
        stringSerde.serialize(aString, tupleBuilder.getDataOutput());
        tupleBuilder.addFieldEndOffset();

        aString.setValue(feedPolicy.getPolicyName());
        stringSerde.serialize(aString, tupleBuilder.getDataOutput());
        tupleBuilder.addFieldEndOffset();

        recordBuilder.reset(feedPolicyEntity.getRecordType());

        // write field 0
        if (feedPolicyEntity.databaseNameIndex() >= 0) {
            fieldValue.reset();
            aString.setValue(feedPolicy.getDatabaseName());
            stringSerde.serialize(aString, fieldValue.getDataOutput());
            recordBuilder.addField(feedPolicyEntity.databaseNameIndex(), fieldValue);
        }
        fieldValue.reset();
        aString.setValue(dataverseCanonicalName);
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        recordBuilder.addField(feedPolicyEntity.dataverseNameIndex(), fieldValue);

        // write field 1
        fieldValue.reset();
        aString.setValue(feedPolicy.getPolicyName());
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        recordBuilder.addField(feedPolicyEntity.policyNameIndex(), fieldValue);

        // write field 2
        fieldValue.reset();
        aString.setValue(feedPolicy.getDescription());
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        recordBuilder.addField(feedPolicyEntity.descriptionIndex(), fieldValue);

        // write field 3 (properties)
        Map<String, String> properties = feedPolicy.getProperties();
        UnorderedListBuilder listBuilder = new UnorderedListBuilder();
        listBuilder.reset((AUnorderedListType) feedPolicyEntity.getRecordType().getFieldTypes()[feedPolicyEntity
                .propertiesIndex()]);
        ArrayBackedValueStorage itemValue = new ArrayBackedValueStorage();
        for (Map.Entry<String, String> property : properties.entrySet()) {
            String name = property.getKey();
            String value = property.getValue();
            itemValue.reset();
            writePropertyTypeRecord(name, value, itemValue.getDataOutput());
            listBuilder.addItem(itemValue);
        }
        fieldValue.reset();
        listBuilder.write(fieldValue.getDataOutput(), true);
        recordBuilder.addField(feedPolicyEntity.propertiesIndex(), fieldValue);

        // write record
        recordBuilder.write(tupleBuilder.getDataOutput(), true);
        tupleBuilder.addFieldEndOffset();

        tuple.reset(tupleBuilder.getFieldEndOffsets(), tupleBuilder.getByteArray());
        return tuple;
    }

    private void writePropertyTypeRecord(String name, String value, DataOutput out) throws HyracksDataException {
        IARecordBuilder propertyRecordBuilder = new RecordBuilder();
        ArrayBackedValueStorage fieldValue = new ArrayBackedValueStorage();
        propertyRecordBuilder.reset(MetadataRecordTypes.POLICY_PARAMS_RECORDTYPE);
        AMutableString aString = new AMutableString("");

        // write field 0
        fieldValue.reset();
        aString.setValue(name);
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        propertyRecordBuilder.addField(0, fieldValue);

        // write field 1
        fieldValue.reset();
        aString.setValue(value);
        stringSerde.serialize(aString, fieldValue.getDataOutput());
        propertyRecordBuilder.addField(1, fieldValue);

        propertyRecordBuilder.write(out, true);
    }
}
