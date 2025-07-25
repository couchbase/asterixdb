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
package org.apache.asterix.optimizer.rules.pushdown.processor;

import static org.apache.asterix.metadata.utils.PushdownUtil.isProhibitedFilterFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.asterix.common.config.DatasetConfig;
import org.apache.asterix.metadata.entities.Dataset;
import org.apache.asterix.metadata.utils.DatasetUtil;
import org.apache.asterix.metadata.utils.PushdownUtil;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ARecordType;
import org.apache.asterix.optimizer.rules.pushdown.PushdownContext;
import org.apache.asterix.optimizer.rules.pushdown.descriptor.ScanDefineDescriptor;
import org.apache.asterix.optimizer.rules.pushdown.descriptor.UseDescriptor;
import org.apache.asterix.optimizer.rules.pushdown.schema.AnyExpectedSchemaNode;
import org.apache.asterix.optimizer.rules.pushdown.schema.ExpectedSchemaNodeType;
import org.apache.asterix.optimizer.rules.pushdown.schema.IExpectedSchemaNode;
import org.apache.asterix.optimizer.rules.pushdown.visitor.ArrayPathCheckerVisitor;
import org.apache.asterix.optimizer.rules.pushdown.visitor.ColumnFilterPathBuilderVisitor;
import org.apache.asterix.optimizer.rules.pushdown.visitor.ExpressionToExpectedSchemaNodeVisitor;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.functions.AlgebricksBuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.functions.IFunctionInfo;

/**
 * Computes a filter expression that can be pushed down to {@link DatasetConfig.DatasetFormat#COLUMN} datasets.
 * The computed filter expression then will be evaluated to determine if a record should be assembled and returned as
 * a result of a data-scan or not.
 */
public class ColumnFilterPushdownProcessor extends AbstractFilterPushdownProcessor {
    protected final ExpressionToExpectedSchemaNodeVisitor exprToNodeVisitor;
    protected final ColumnFilterPathBuilderVisitor pathBuilderVisitor;
    protected final Map<ILogicalExpression, ARecordType> paths;
    protected final ArrayPathCheckerVisitor checkerVisitor;

    public ColumnFilterPushdownProcessor(PushdownContext pushdownContext, IOptimizationContext context) {
        super(pushdownContext, context);
        exprToNodeVisitor = new ExpressionToExpectedSchemaNodeVisitor();
        pathBuilderVisitor = new ColumnFilterPathBuilderVisitor();
        paths = new HashMap<>();
        checkerVisitor = new ArrayPathCheckerVisitor();
    }

    @Override
    protected boolean skip(ScanDefineDescriptor scanDefineDescriptor) throws AlgebricksException {
        Dataset dataset = scanDefineDescriptor.getDataset();
        LogicalOperatorTag scanOpTag = scanDefineDescriptor.getOperator().getOperatorTag();
        /*
         * Only use the filter with data-scan. For index-search (unnest-map), this could be expensive as this
         * requires to rewind the columnar readers for each point-lookup -- decoding 1000s of values for each
         * point-lookup. Hence, the query should rely on the secondary-index filtration and not the columnar filter.
         */
        return scanOpTag != LogicalOperatorTag.DATASOURCESCAN
                || dataset.getDatasetFormatInfo().getFormat() != DatasetConfig.DatasetFormat.COLUMN
                || !DatasetUtil.isFilterPushdownSupported(dataset);
    }

    @Override
    protected void prepareScan(ScanDefineDescriptor scanDefineDescriptor) {
        exprToNodeVisitor.reset(scanDefineDescriptor);
    }

    @Override
    protected void preparePushdown(UseDescriptor useDescriptor, ScanDefineDescriptor scanDescriptor)
            throws AlgebricksException {
        ILogicalOperator useOp = useDescriptor.getOperator();
        ILogicalOperator scanOp = scanDescriptor.getOperator();
        exprToNodeVisitor.setTypeEnv(PushdownUtil.getTypeEnv(useOp, scanOp, context));
    }

    @Override
    protected boolean isNotPushable(AbstractFunctionCallExpression expression) {
        return isProhibitedFilterFunction(expression);
    }

    @Override
    protected FilterBranch handleCompare(AbstractFunctionCallExpression expression, int depth,
            UseDescriptor currentDescriptor) throws AlgebricksException {
        List<Mutable<ILogicalExpression>> args = expression.getArguments();

        Mutable<ILogicalExpression> leftRef = args.get(0);
        Mutable<ILogicalExpression> rightRef = args.get(1);

        ILogicalExpression left = leftRef.getValue();
        ILogicalExpression right = rightRef.getValue();

        //If the left or right is handlePath (like getField), then the right or left shouldn't be an array
        FilterBranch leftBranch = pushdownFilterExpression(left, currentDescriptor, depth + 1);
        FilterBranch rightBranch = pushdownFilterExpression(right, currentDescriptor, depth + 1);

        FilterBranch result = FilterBranch.andOutput(leftBranch, rightBranch, FilterBranch.COMPARE);
        if (result == FilterBranch.NA) {
            //If the result is NA, then we cannot push down the filter
            return FilterBranch.NA;
        }

        boolean pushdown = true;
        //If the value is a filterPath, means it is coming from the expression tree.
        if (leftBranch == FilterBranch.FILTER_PATH && rightBranch == FilterBranch.FILTER_PATH) {
            return FilterBranch.COMPARE;
        } else if (leftBranch == FilterBranch.FILTER_PATH) {
            // if the expression return type is an array or any, we cannot push it down
            pushdown = !expressionReturnsArray(right, currentDescriptor.getOperator());
        } else if (rightBranch == FilterBranch.FILTER_PATH) {
            pushdown = !expressionReturnsArray(left, currentDescriptor.getOperator());
        }

        return pushdown ? result : FilterBranch.NA;
    }

    @Override
    protected FilterBranch handlePath(AbstractFunctionCallExpression expression, IExpectedSchemaNode node)
            throws AlgebricksException {
        if (node.getType() != ExpectedSchemaNodeType.ANY) {
            return FilterBranch.NA;
        }
        paths.put(expression, pathBuilderVisitor.buildPath((AnyExpectedSchemaNode) node));
        return FilterBranch.FILTER_PATH;
    }

    @Override
    protected boolean pushdownFilter(ScanDefineDescriptor scanDescriptor) throws AlgebricksException {
        paths.clear();
        checkerVisitor.beforeVisit();
        return super.pushdownFilter(scanDescriptor);
    }

    @Override
    protected void putFilterInformation(ScanDefineDescriptor scanDefineDescriptor, ILogicalExpression inlinedExpr)
            throws AlgebricksException {
        if (checkerVisitor.containsMultipleArrayPaths(paths.values())) {
            // Cannot pushdown a filter with multiple unnest
            // TODO allow rewindable column readers for filters
            // TODO this is a bit conservative (maybe too conservative) as we can push part of expression down
            return;
        }

        ILogicalExpression filterExpr = scanDefineDescriptor.getFilterExpression();
        if (filterExpr != null) {
            filterExpr = andExpression(filterExpr, inlinedExpr);
            scanDefineDescriptor.setFilterExpression(filterExpr);
        } else {
            scanDefineDescriptor.setFilterExpression(inlinedExpr);
        }

        scanDefineDescriptor.getFilterPaths().putAll(paths);
    }

    @Override
    protected IExpectedSchemaNode getPathNode(AbstractFunctionCallExpression expression) throws AlgebricksException {
        return expression.accept(exprToNodeVisitor, null);
    }

    protected final AbstractFunctionCallExpression andExpression(ILogicalExpression filterExpr,
            ILogicalExpression inlinedExpr) {
        AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) filterExpr;
        if (!BuiltinFunctions.AND.equals(funcExpr.getFunctionIdentifier())) {
            IFunctionInfo fInfo = context.getMetadataProvider().lookupFunction(AlgebricksBuiltinFunctions.AND);
            List<Mutable<ILogicalExpression>> args = new ArrayList<>();
            args.add(new MutableObject<>(filterExpr));
            funcExpr = new ScalarFunctionCallExpression(fInfo, args);
        }
        funcExpr.getArguments().add(new MutableObject<>(inlinedExpr));
        return funcExpr;
    }
}
