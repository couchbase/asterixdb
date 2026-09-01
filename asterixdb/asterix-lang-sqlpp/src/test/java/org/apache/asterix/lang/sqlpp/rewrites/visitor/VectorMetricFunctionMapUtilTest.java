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

package org.apache.asterix.lang.sqlpp.rewrites.visitor;

import org.apache.asterix.common.vector.VectorSimilarityMetric;
import org.apache.asterix.lang.common.util.VectorMetricFunctionMapUtil;
import org.junit.Assert;
import org.junit.Test;

public class VectorMetricFunctionMapUtilTest {

    @Test
    public void resolvesEuclideanMetrics() {
        Assert.assertEquals("euclidean-distance", VectorMetricFunctionMapUtil.resolve("L2").orElseThrow());
        Assert.assertEquals("euclidean-distance", VectorMetricFunctionMapUtil.resolve("EUCLIDEAN").orElseThrow());
    }

    @Test
    public void resolvesEuclideanSquaredMetrics() {
        Assert.assertEquals("euclidean-squared-distance",
                VectorMetricFunctionMapUtil.resolve("L2_SQUARED").orElseThrow());
        Assert.assertEquals("euclidean-squared-distance",
                VectorMetricFunctionMapUtil.resolve("EUCLIDEAN_SQUARED").orElseThrow());
    }

    @Test
    public void resolvesCosineMetric() {
        Assert.assertEquals("cosine-distance", VectorMetricFunctionMapUtil.resolve("COSINE").orElseThrow());
    }

    @Test
    public void resolvesDotMetric() {
        Assert.assertEquals("dot-distance", VectorMetricFunctionMapUtil.resolve("DOT").orElseThrow());
    }

    @Test
    public void rejectsRemovedAliases() {
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("cosine_similarity").isEmpty());
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("dot_product").isEmpty());
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("l2_distance").isEmpty());
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("euclidean_distance").isEmpty());
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("cosine_distance").isEmpty());
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("dot_distance").isEmpty());
    }

    @Test
    public void rejectsUnknownMetric() {
        Assert.assertTrue(VectorMetricFunctionMapUtil.resolve("unknown").isEmpty());
    }

    /**
     * The contract this class exists to keep: a spelling the DDL accepts is one a query accepts, and
     * the reverse. Both resolve through {@link VectorSimilarityMetric}, so a divergence here means
     * that delegation is broken.
     */
    @Test
    public void everyDdlAliasResolvesHereAndNothingElseDoes() {
        for (VectorSimilarityMetric metric : VectorSimilarityMetric.values()) {
            for (String alias : metric.aliases()) {
                Assert.assertTrue(alias, VectorMetricFunctionMapUtil.resolve(alias).isPresent());
            }
        }
        // and the converse: anything this class resolves is a metric the DDL resolves too
        for (String spelling : new String[] { "l2", "L2", " euclidean ", "EUCLIDEAN_SQUARED", "cosine", "dot" }) {
            Assert.assertEquals(spelling, VectorMetricFunctionMapUtil.resolve(spelling).isPresent(),
                    VectorSimilarityMetric.fromAlias(spelling) != null);
        }
    }

    @Test
    public void spellingsRejectedByTheDdlAreRejectedHere() {
        for (String spelling : new String[] { "cosine similarity", "euclidean-squared", "l2-squared", "manhattan" }) {
            Assert.assertNull(spelling, VectorSimilarityMetric.fromAlias(spelling));
            Assert.assertTrue(spelling, VectorMetricFunctionMapUtil.resolve(spelling).isEmpty());
        }
    }
}
