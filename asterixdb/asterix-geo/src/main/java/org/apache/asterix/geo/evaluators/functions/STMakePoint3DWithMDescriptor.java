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
package org.apache.asterix.geo.evaluators.functions;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.asterix.dataflow.data.nontagged.serde.AGeometrySerializerDeserializer;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.functions.IFunctionDescriptorFactory;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IEvaluatorContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;
import org.apache.hyracks.dataflow.common.data.accessors.IFrameTupleReference;
import org.locationtech.jts.geom.CoordinateXYZM;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

/**
 * TODO: Support writing geometry with 4 dimensions
 * JTS WKBWriter currently does not support writing 4 dimensions and loses the detail.
 * See https://github.com/locationtech/jts/issues/733 for more details.
 */
public class STMakePoint3DWithMDescriptor extends AbstractGetValDescriptor {

    private static final long serialVersionUID = 1L;
    public static final IFunctionDescriptorFactory FACTORY = STMakePoint3DWithMDescriptor::new;

    @Override
    public IScalarEvaluatorFactory createEvaluatorFactory(final IScalarEvaluatorFactory[] args) {
        return new IScalarEvaluatorFactory() {
            private static final long serialVersionUID = 1L;

            @Override
            public IScalarEvaluator createScalarEvaluator(final IEvaluatorContext ctx) throws HyracksDataException {
                return new STMakePoint3DWithMEvaluator(args, ctx);
            }
        };
    }

    @Override
    public FunctionIdentifier getIdentifier() {
        return BuiltinFunctions.ST_MAKE_POINT3D_M;
    }

    private class STMakePoint3DWithMEvaluator implements IScalarEvaluator {

        private final ArrayBackedValueStorage resultStorage;
        private final DataOutput out;
        private IPointable inputArg0;
        private IPointable inputArg1;
        private IPointable inputArg2;
        private IPointable inputArg3;
        private final IScalarEvaluator eval0;
        private final IScalarEvaluator eval1;
        private final IScalarEvaluator eval2;
        private final IScalarEvaluator eval3;
        private Point point;
        private final GeometryFactory geometryFactory;

        public STMakePoint3DWithMEvaluator(IScalarEvaluatorFactory[] args, IEvaluatorContext ctx)
                throws HyracksDataException {
            resultStorage = new ArrayBackedValueStorage();
            out = resultStorage.getDataOutput();
            inputArg0 = new VoidPointable();
            inputArg1 = new VoidPointable();
            inputArg2 = new VoidPointable();
            inputArg3 = new VoidPointable();
            eval0 = args[0].createScalarEvaluator(ctx);
            eval1 = args[1].createScalarEvaluator(ctx);
            eval2 = args[2].createScalarEvaluator(ctx);
            eval3 = args[3].createScalarEvaluator(ctx);
            geometryFactory = new GeometryFactory();
        }

        @Override
        public void evaluate(IFrameTupleReference tuple, IPointable result) throws HyracksDataException {
            eval0.evaluate(tuple, inputArg0);
            eval1.evaluate(tuple, inputArg1);
            eval2.evaluate(tuple, inputArg2);
            eval3.evaluate(tuple, inputArg3);

            byte[] bytes0 = inputArg0.getByteArray();
            int offset0 = inputArg0.getStartOffset();
            byte[] bytes1 = inputArg1.getByteArray();
            int offset1 = inputArg1.getStartOffset();
            byte[] bytes2 = inputArg2.getByteArray();
            int offset2 = inputArg2.getStartOffset();
            byte[] bytes3 = inputArg3.getByteArray();
            int offset3 = inputArg3.getStartOffset();

            resultStorage.reset();
            try {
                out.writeByte(ATypeTag.SERIALIZED_GEOMETRY_TYPE_TAG);
                CoordinateXYZM coordinate = new CoordinateXYZM(getVal(bytes0, offset0), getVal(bytes1, offset1),
                        getVal(bytes2, offset2), getVal(bytes3, offset3));
                point = geometryFactory.createPoint(coordinate);
                AGeometrySerializerDeserializer.INSTANCE.serialize(point, out);
            } catch (IOException e1) {
                throw HyracksDataException.create(e1);
            }
            result.set(resultStorage);
        }
    }
}
