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

package org.apache.asterix.runtime.evaluators.constructors;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.hierachy.ATypeHierarchy;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.api.context.IEvaluatorContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.SourceLocation;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.util.ArrayBackedValueStorage;

public abstract class AbstractIntConstructorEvaluator extends AbstractConstructorEvaluator {

    protected final ArrayBackedValueStorage tmpStorage;
    protected final DataOutput tmpOut;

    public AbstractIntConstructorEvaluator(IEvaluatorContext ctx, IScalarEvaluator inputEval,
            SourceLocation sourceLoc) {
        super(ctx, inputEval, sourceLoc);
        tmpStorage = new ArrayBackedValueStorage();
        tmpOut = tmpStorage.getDataOutput();
    }

    protected void promoteNumeric(ATypeTag inputType, byte[] bytes, int startOffset, int len, IPointable result)
            throws HyracksDataException {
        resultStorage.reset();
        try {
            ATypeHierarchy.getTypePromoteComputer(inputType, getTargetType().getTypeTag()).convertType(bytes,
                    startOffset, len, out);
            result.set(resultStorage);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }

    protected void demoteNumeric(ATypeTag inputType, byte[] bytes, int startOffset, int len, IPointable result)
            throws HyracksDataException {
        resultStorage.reset();
        try {
            ATypeHierarchy.getTypeDemoteComputer(inputType, getTargetType().getTypeTag(), false).convertType(bytes,
                    startOffset, len, out);
            result.set(resultStorage);
        } catch (IOException e) {
            throw HyracksDataException.create(e);
        }
    }
}
