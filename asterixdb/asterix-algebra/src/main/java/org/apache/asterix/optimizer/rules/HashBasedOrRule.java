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
package org.apache.asterix.optimizer.rules;

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.hierachy.ATypeHierarchy;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ScalarFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.VariableReferenceExpression;
import org.apache.hyracks.algebricks.core.algebra.functions.AlgebricksBuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.visitors.ILogicalExpressionReferenceTransform;
import org.apache.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

public class HashBasedOrRule implements IAlgebraicRewriteRule {

    public static final String HASH_BASED_OR_OPTION = "hash_based_or";
    private static final boolean HASH_BASED_OR_OPTION_DEFAULT = false;

    private final HashBasedOrTransformer transform = new HashBasedOrTransformer();
    private Boolean isRuleEnabled;

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        MetadataProvider metadataProvider = (MetadataProvider) context.getMetadataProvider();
        if (isRuleEnabled == null) {
            isRuleEnabled = metadataProvider.getBooleanProperty(HASH_BASED_OR_OPTION, HASH_BASED_OR_OPTION_DEFAULT);
        }
        if (!isRuleEnabled) {
            return false;
        }

        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (context.checkIfInDontApplySet(this, op)) {
            return false;
        }
        context.addToDontApplySet(this, op);
        return op.acceptExpressionTransform(transform);
    }

    private static class HashBasedOrTransformer implements ILogicalExpressionReferenceTransform {

        @Override
        public boolean transform(Mutable<ILogicalExpression> exprRef) throws AlgebricksException {
            ILogicalExpression expr = exprRef.getValue();
            if (expr.getExpressionTag() != LogicalExpressionTag.FUNCTION_CALL) {
                return false;
            }

            AbstractFunctionCallExpression funcExpr = (AbstractFunctionCallExpression) expr;

            boolean changed = false;
            for (Mutable<ILogicalExpression> arg : funcExpr.getArguments()) {
                changed |= transform(arg);
            }
            if (funcExpr.getFunctionIdentifier().equals(AlgebricksBuiltinFunctions.OR)) {
                changed |= tryRewriteOr(exprRef, funcExpr);
            }

            return changed;
        }

        private static boolean tryRewriteOr(Mutable<ILogicalExpression> exprRef,
                AbstractFunctionCallExpression funcExpr) {
            List<Mutable<ILogicalExpression>> orArgs = funcExpr.getArguments();
            LogicalVariable commonVar = null;
            List<ILogicalExpression> constants = new ArrayList<>();
            ATypeTag tag = null;

            for (Mutable<ILogicalExpression> arg : orArgs) {
                ILogicalExpression argExpr = arg.getValue();
                if (!apply(argExpr)) {
                    return false;
                }

                AbstractFunctionCallExpression eqExpr = (AbstractFunctionCallExpression) argExpr;
                List<Mutable<ILogicalExpression>> eqArgs = eqExpr.getArguments();
                ILogicalExpression left = eqArgs.get(0).getValue();
                ILogicalExpression right = eqArgs.get(1).getValue();
                VariableReferenceExpression varExpr;
                ConstantExpression constExpr;
                if (left.getExpressionTag() == LogicalExpressionTag.VARIABLE
                        && right.getExpressionTag() == LogicalExpressionTag.CONSTANT) {
                    varExpr = (VariableReferenceExpression) left;
                    constExpr = (ConstantExpression) right;
                } else if (right.getExpressionTag() == LogicalExpressionTag.VARIABLE
                        && left.getExpressionTag() == LogicalExpressionTag.CONSTANT) {
                    varExpr = (VariableReferenceExpression) right;
                    constExpr = (ConstantExpression) left;
                } else {
                    return false;
                }

                if (tag == null) {
                    tag = ((AsterixConstantValue) constExpr.getValue()).getObject().getType().getTypeTag();
                } else if (!ATypeHierarchy.isCompatible(tag,
                        ((AsterixConstantValue) constExpr.getValue()).getObject().getType().getTypeTag())) {
                    return false;
                }
                LogicalVariable var = varExpr.getVariableReference();
                if (commonVar == null) {
                    commonVar = var;
                } else if (!commonVar.equals(var)) {
                    return false;
                }
                constants.add(constExpr);
            }

            if (commonVar == null || constants.size() < 2) {
                return false;
            }

            ScalarFunctionCallExpression hashmapOrExpr = new ScalarFunctionCallExpression(
                    BuiltinFunctions.getBuiltinFunctionInfo(BuiltinFunctions.HASH_BASED_OR));
            hashmapOrExpr.setSourceLocation(funcExpr.getSourceLocation());

            VariableReferenceExpression newVarRef = new VariableReferenceExpression(commonVar);
            newVarRef.setSourceLocation(funcExpr.getSourceLocation());
            hashmapOrExpr.getArguments().add(new MutableObject<>(newVarRef));

            for (ILogicalExpression constExpr : constants) {
                hashmapOrExpr.getArguments().add(new MutableObject<>(constExpr));
            }

            exprRef.setValue(hashmapOrExpr);
            return true;
        }
    }

    private static boolean apply(ILogicalExpression argExpr) {
        return argExpr.getExpressionTag() == LogicalExpressionTag.FUNCTION_CALL
                && ((AbstractFunctionCallExpression) argExpr).getFunctionIdentifier()
                        .equals(AlgebricksBuiltinFunctions.EQ)
                && ((AbstractFunctionCallExpression) argExpr).getArguments().size() == 2;
    }
}
