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
package org.apache.asterix.metadata.utils.filter;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.asterix.column.filter.range.IColumnRangeFilterEvaluatorFactory;
import org.apache.asterix.column.filter.range.evaluator.ORColumnFilterEvaluatorFactory;
import org.apache.asterix.om.base.AInt64;
import org.apache.asterix.om.base.AString;
import org.apache.asterix.om.base.IAObject;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.om.utils.ProjectionFiltrationTypeUtil;
import org.apache.asterix.runtime.projection.ColumnDatasetProjectionFiltrationInfo;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.functions.AlgebricksBuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.junit.Assert;
import org.junit.Test;

public class ColumnRangeFilterBuilderTest {

    private static final LogicalVariable RECORD = new LogicalVariable(1);

    /**
     * A long IN list reaches the range filter as an OR of that many equalities. The filter is serialized with the
     * job, so it must not be as deep as the list: a chain of 3,000 does not fit a 1 MB stack.
     */
    @Test
    public void longDisjunctionSerializes() throws Exception {
        List<Mutable<ILogicalExpression>> disjuncts = new ArrayList<>();
        for (long i = 0; i < 20_000; i++) {
            disjuncts.add(new MutableObject<>(call(AlgebricksBuiltinFunctions.EQ, fieldA(), constant(new AInt64(i)))));
        }
        IColumnRangeFilterEvaluatorFactory filter = build(call(AlgebricksBuiltinFunctions.OR, disjuncts));
        Assert.assertTrue(filter instanceof ORColumnFilterEvaluatorFactory);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread serializer = new Thread(null, () -> {
            try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                out.writeObject(filter);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "range-filter-serializer", 1L << 20);
        serializer.start();
        serializer.join();
        if (failure.get() != null) {
            throw new AssertionError("the range filter of a 20,000-way OR does not serialize", failure.get());
        }
    }

    private static IColumnRangeFilterEvaluatorFactory build(ILogicalExpression rangeFilter) {
        Map<ILogicalExpression, ARecordType> filterPaths = new HashMap<>();
        filterPaths.put(fieldA(), ProjectionFiltrationTypeUtil.getPathRecordType(Collections.singletonList("a")));
        return new ColumnRangeFilterBuilder(
                new ColumnDatasetProjectionFiltrationInfo(ProjectionFiltrationTypeUtil.ALL_FIELDS_TYPE, null,
                        Collections.emptyMap(), filterPaths, null, rangeFilter)).build();
    }

    private static ILogicalExpression fieldA() {
        return call(BuiltinFunctions.FIELD_ACCESS_BY_NAME, new VariableReferenceExpression(RECORD),
                constant(new AString("a")));
    }

    private static ConstantExpression constant(IAObject value) {
        return new ConstantExpression(new AsterixConstantValue(value));
    }

    private static ScalarFunctionCallExpression call(FunctionIdentifier fid, ILogicalExpression... args) {
        List<Mutable<ILogicalExpression>> argRefs = new ArrayList<>();
        for (ILogicalExpression arg : args) {
            argRefs.add(new MutableObject<>(arg));
        }
        return call(fid, argRefs);
    }

    private static ScalarFunctionCallExpression call(FunctionIdentifier fid, List<Mutable<ILogicalExpression>> args) {
        return new ScalarFunctionCallExpression(BuiltinFunctions.getBuiltinFunctionInfo(fid), args);
    }
}
