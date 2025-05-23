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
package org.apache.asterix.om.dictionary;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hyracks.api.dataflow.value.IBinaryHashFunction;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.accessors.PointableBinaryHashFunctionFactory;
import org.apache.hyracks.data.std.api.IValueReference;
import org.apache.hyracks.data.std.primitive.UTF8StringPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * @deprecated Use {@link FieldNamesTrieDictionary}
 */
@Deprecated
public class FieldNamesHashDictionary extends AbstractFieldNamesDictionary {
    //For both declared and inferred fields
    private final List<IValueReference> fieldNames;
    private final Object2IntMap<String> declaredFieldNamesToIndexMap;
    private final Int2IntMap hashToFieldNameIndexMap;
    private final IBinaryHashFunction fieldNameHashFunction;

    //For lookups
    private final ArrayBackedValueStorage lookupStorage;

    public FieldNamesHashDictionary() {
        this(new ArrayList<>(), new Object2IntOpenHashMap<>(), new Int2IntOpenHashMap());
    }

    private FieldNamesHashDictionary(List<IValueReference> fieldNames,
            Object2IntMap<String> declaredFieldNamesToIndexMap, Int2IntMap hashToFieldNameIndexMap) {
        super();
        this.fieldNames = fieldNames;
        this.declaredFieldNamesToIndexMap = declaredFieldNamesToIndexMap;
        this.hashToFieldNameIndexMap = hashToFieldNameIndexMap;
        fieldNameHashFunction =
                new PointableBinaryHashFunctionFactory(UTF8StringPointable.FACTORY).createBinaryHashFunction();
        lookupStorage = new ArrayBackedValueStorage();
    }

    @Override
    public List<IValueReference> getFieldNames() {
        return fieldNames;
    }

    //TODO solve collision (they're so rare that I haven't seen any)
    @Override
    public int getOrCreateFieldNameIndex(IValueReference fieldName) throws HyracksDataException {
        if (fieldName == DUMMY_FIELD_NAME) {
            return DUMMY_FIELD_NAME_INDEX;
        }

        int hash = getHash(fieldName);
        if (!hashToFieldNameIndexMap.containsKey(hash)) {
            int index = addFieldName(creatFieldName(fieldName), hash);
            hashToFieldNameIndexMap.put(hash, index);
            return index;
        }
        return hashToFieldNameIndexMap.get(hash);
    }

    @Override
    public int getOrCreateFieldNameIndex(String fieldName) throws HyracksDataException {
        if (!declaredFieldNamesToIndexMap.containsKey(fieldName)) {
            IValueReference serializedFieldName = creatFieldName(fieldName);
            int hash = getHash(serializedFieldName);
            int index = addFieldName(serializedFieldName, hash);
            declaredFieldNamesToIndexMap.put(fieldName, index);
            return index;
        }
        return declaredFieldNamesToIndexMap.getInt(fieldName);
    }

    @Override
    public int getFieldNameIndex(String fieldName) throws HyracksDataException {
        lookupStorage.reset();
        serializeFieldName(fieldName, lookupStorage);
        return hashToFieldNameIndexMap.getOrDefault(getHash(lookupStorage), -1);
    }

    private int getHash(IValueReference fieldName) throws HyracksDataException {
        byte[] object = fieldName.getByteArray();
        int start = fieldName.getStartOffset();
        int length = fieldName.getLength();

        return fieldNameHashFunction.hash(object, start, length);
    }

    private int addFieldName(IValueReference fieldName, int hash) {
        int index = fieldNames.size();
        hashToFieldNameIndexMap.put(hash, index);
        fieldNames.add(fieldName);
        return index;
    }

    @Override
    public IValueReference getFieldName(int index) {
        if (index == DUMMY_FIELD_NAME_INDEX) {
            return DUMMY_FIELD_NAME;
        }
        return fieldNames.get(index);
    }

    @Override
    public void serialize(DataOutput output) throws IOException {
        output.writeInt(fieldNames.size());
        for (IValueReference fieldName : fieldNames) {
            output.writeInt(fieldName.getLength());
            output.write(fieldName.getByteArray(), fieldName.getStartOffset(), fieldName.getLength());
        }

        output.writeInt(declaredFieldNamesToIndexMap.size());
        for (Object2IntMap.Entry<String> declaredFieldIndex : declaredFieldNamesToIndexMap.object2IntEntrySet()) {
            output.writeUTF(declaredFieldIndex.getKey());
            output.writeInt(declaredFieldIndex.getIntValue());
        }

        for (Int2IntMap.Entry hashIndex : hashToFieldNameIndexMap.int2IntEntrySet()) {
            output.writeInt(hashIndex.getIntKey());
            output.writeInt(hashIndex.getIntValue());
        }
    }

    public static FieldNamesHashDictionary deserialize(DataInput input) throws IOException {
        int numberOfFieldNames = input.readInt();

        List<IValueReference> fieldNames = new ArrayList<>();
        deserializeFieldNames(input, fieldNames, numberOfFieldNames);

        Object2IntMap<String> declaredFieldNamesToIndexMap = new Object2IntOpenHashMap<>();
        deserializeDeclaredFieldNames(input, declaredFieldNamesToIndexMap);

        Int2IntMap hashToFieldNameIndexMap = new Int2IntOpenHashMap();
        deserializeHashToFieldNameIndex(input, hashToFieldNameIndexMap, numberOfFieldNames);

        return new FieldNamesHashDictionary(fieldNames, declaredFieldNamesToIndexMap, hashToFieldNameIndexMap);
    }

    @Override
    public void abort(DataInputStream input) throws IOException {
        int numberOfFieldNames = input.readInt();

        fieldNames.clear();
        deserializeFieldNames(input, fieldNames, numberOfFieldNames);

        declaredFieldNamesToIndexMap.clear();
        deserializeDeclaredFieldNames(input, declaredFieldNamesToIndexMap);

        hashToFieldNameIndexMap.clear();
        deserializeHashToFieldNameIndex(input, hashToFieldNameIndexMap, numberOfFieldNames);
    }

    private static void deserializeDeclaredFieldNames(DataInput input,
            Object2IntMap<String> declaredFieldNamesToIndexMap) throws IOException {
        int numberOfDeclaredFieldNames = input.readInt();
        for (int i = 0; i < numberOfDeclaredFieldNames; i++) {
            String fieldName = input.readUTF();
            int fieldNameIndex = input.readInt();
            declaredFieldNamesToIndexMap.put(fieldName, fieldNameIndex);
        }
    }

    private static void deserializeHashToFieldNameIndex(DataInput input, Int2IntMap hashToFieldNameIndexMap,
            int numberOfFieldNames) throws IOException {
        for (int i = 0; i < numberOfFieldNames; i++) {
            int hash = input.readInt();
            int fieldNameIndex = input.readInt();
            hashToFieldNameIndexMap.put(hash, fieldNameIndex);
        }
    }
}
