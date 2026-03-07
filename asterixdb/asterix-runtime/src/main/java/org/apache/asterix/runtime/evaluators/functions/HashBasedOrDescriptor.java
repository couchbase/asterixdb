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
package org.apache.asterix.runtime.evaluators.functions;

import java.io.DataOutput;

import org.apache.asterix.formats.nontagged.BinaryComparatorFactoryProvider;
import org.apache.asterix.formats.nontagged.BinaryHashFunctionFactoryProvider;
import org.apache.asterix.formats.nontagged.SerializerDeserializerProvider;
import org.apache.asterix.om.base.ABoolean;
import org.apache.asterix.om.base.AMissing;
import org.apache.asterix.om.base.ANull;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.functions.IFunctionDescriptor;
import org.apache.asterix.om.functions.IFunctionDescriptorFactory;
import org.apache.asterix.om.functions.IFunctionTypeInferer;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.om.types.EnumDeserializer;
import org.apache.asterix.om.types.IAType;
import org.apache.asterix.om.types.hierachy.ATypeHierarchy;
import org.apache.asterix.runtime.evaluators.base.AbstractScalarFunctionDynamicDescriptor;
import org.apache.asterix.runtime.functions.FunctionTypeInferers;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IEvaluatorContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.IBinaryHashFunction;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.data.std.util.BinaryEntry;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;

public class HashBasedOrDescriptor extends AbstractScalarFunctionDynamicDescriptor {

    private static final long serialVersionUID = 1L;
    /** Dummy value for set semantics (BinaryHashMap requires key+value; we only need key presence). */
    private static final byte[] DUMMY_VALUE = new byte[] { 0 };

    /** Same defaults as RecordAddFieldsDescriptor for BinaryHashMap. */
    private static final int TABLE_SIZE = 100;
    private static final int TABLE_FRAME_SIZE = 32768;

    public static final IFunctionDescriptorFactory FACTORY = new IFunctionDescriptorFactory() {
        @Override
        public IFunctionDescriptor createFunctionDescriptor() {
            return new HashBasedOrDescriptor();
        }

        @Override
        public IFunctionTypeInferer createFunctionTypeInferer() {
            return FunctionTypeInferers.SET_OR_TYPES;
        }
    };

    protected IAType elementType;

    @Override
    public void setImmutableStates(Object... types) {
        elementType = (IAType) types[0];
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return BuiltinFunctions.HASH_BASED_OR;
    }

    @Override
    public IScalarEvaluatorFactory createEvaluatorFactory(final IScalarEvaluatorFactory[] args) {
        return new IScalarEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IScalarEvaluator createScalarEvaluator(final IEvaluatorContext ctx) throws HyracksDataException {
                final IScalarEvaluator valueEval = args[0].createScalarEvaluator(ctx);
                final int numConst = args.length - 1;
                final IScalarEvaluator[] constEvals = new IScalarEvaluator[numConst];
                for (int i = 0; i < numConst; i++) {
                    constEvals[i] = args[i + 1].createScalarEvaluator(ctx);
                }

                final IBinaryHashFunction putHashFunc = BinaryHashFunctionFactoryProvider.INSTANCE
                        .getBinaryHashFunctionFactory(elementType).createBinaryHashFunction();
                final IBinaryHashFunction getHashFunc = BinaryHashFunctionFactoryProvider.INSTANCE
                        .getBinaryHashFunctionFactory(elementType).createBinaryHashFunction();
                final IBinaryComparator cmp = BinaryComparatorFactoryProvider.INSTANCE
                        .getBinaryComparatorFactory(elementType, true).createBinaryComparator();

                return new IScalarEvaluator() {
                    private final ArrayBackedValueStorage resultStorage = new ArrayBackedValueStorage();
                    private final DataOutput output = resultStorage.getDataOutput();
                    private final VoidPointable valuePtr = new VoidPointable();
                    private final VoidPointable constPtr = new VoidPointable();
                    private final BinaryEntry keyEntry = new BinaryEntry();
                    private final BinaryEntry valEntry = new BinaryEntry();
                    private final BinaryHashMap valueSet =
                            new BinaryHashMap(TABLE_SIZE, TABLE_FRAME_SIZE, putHashFunc, getHashFunc, cmp);

                    private boolean setBuilt = false;

                    @SuppressWarnings("unchecked")
                    private final ISerializerDeserializer<ABoolean> booleanSerde =
                            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ABOOLEAN);
                    @SuppressWarnings("unchecked")
                    private final ISerializerDeserializer<ANull> nullSerde =
                            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ANULL);
                    @SuppressWarnings("unchecked")
                    private final ISerializerDeserializer<AMissing> missingSerde =
                            SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.AMISSING);

                    @Override
                    public void evaluate(IFrameTupleReference tuple, IPointable result) throws HyracksDataException {
                        resultStorage.reset();
                        buildSetIfNeeded(tuple);
                        valueEval.evaluate(tuple, valuePtr);
                        byte[] data = valuePtr.getByteArray();
                        int offset = valuePtr.getStartOffset();

                        if (data[offset] == ATypeTag.SERIALIZED_MISSING_TYPE_TAG) {
                            missingSerde.serialize(AMissing.MISSING, output);
                            result.set(resultStorage);
                            return;
                        }
                        if (data[offset] == ATypeTag.SERIALIZED_NULL_TYPE_TAG) {
                            nullSerde.serialize(ANull.NULL, output);
                            result.set(resultStorage);
                            return;
                        }
                        if (!ATypeHierarchy.isCompatible(
                                EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(data[offset]),
                                elementType.getTypeTag())) {
                            booleanSerde.serialize(ABoolean.FALSE, output);
                            result.set(resultStorage);
                            return;
                        }

                        keyEntry.set(data, offset, valuePtr.getLength());
                        BinaryEntry found = valueSet.get(keyEntry);
                        if (found == null) {
                            booleanSerde.serialize(ABoolean.FALSE, output);
                        } else {
                            booleanSerde.serialize(ABoolean.TRUE, output);
                        }
                        result.set(resultStorage);
                    }

                    private void buildSetIfNeeded(IFrameTupleReference tuple) throws HyracksDataException {
                        if (setBuilt) {
                            return;
                        }
                        valEntry.set(DUMMY_VALUE, 0, 1);
                        for (int i = 0; i < numConst; i++) {
                            constEvals[i].evaluate(tuple, constPtr);
                            byte[] data = constPtr.getByteArray();
                            int offset = constPtr.getStartOffset();
                            if (offset < data.length) {
                                keyEntry.set(data, offset, constPtr.getLength());
                                valueSet.put(keyEntry, valEntry);
                            }
                        }
                        setBuilt = true;
                    }
                };
            }
        };
    }
}
