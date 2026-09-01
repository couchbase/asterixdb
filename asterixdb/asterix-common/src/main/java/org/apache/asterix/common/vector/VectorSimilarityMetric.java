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
package org.apache.asterix.common.vector;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for the vector distance metrics the VTree stack understands, together with
 * the string aliases each metric is written as in {@code ann_distance(...)} and in the index
 * {@code similarity} option. The canonical name is the first alias.
 * <p>
 * Every entry point that reads a user-written metric resolves here: the {@code similarity} option on
 * CREATE INDEX, the metric argument of {@code ann_distance} and {@code vector_distance} (through
 * {@code VectorMetricFunctionMapUtil}), the CLUSTER BY {@code similarity} option, and optimizer index
 * selection. One table means a spelling accepted by one is accepted by all of them.
 */
public enum VectorSimilarityMetric {
    EUCLIDEAN("euclidean", "l2"),
    EUCLIDEAN_SQUARED("euclidean_squared", "l2_squared"),
    COSINE("cosine"),
    DOT("dot");

    private final List<String> aliases;

    VectorSimilarityMetric(String... aliases) {
        this.aliases = List.of(aliases);
    }

    /** The canonical (preferred) name for this metric: the first alias. */
    public String canonical() {
        return aliases.get(0);
    }

    /** Every accepted spelling for this metric (lowercase), canonical first. */
    public List<String> aliases() {
        return aliases;
    }

    // Lowercase alias -> metric.
    private static final Map<String, VectorSimilarityMetric> BY_ALIAS;
    static {
        Map<String, VectorSimilarityMetric> byAlias = new HashMap<>();
        for (VectorSimilarityMetric metric : values()) {
            for (String alias : metric.aliases) {
                byAlias.put(normalize(alias), metric);
            }
        }
        BY_ALIAS = Collections.unmodifiableMap(byAlias);
    }

    /**
     * The one spelling rule for a metric name: case-insensitive, surrounding whitespace ignored.
     * Every entry point that accepts a user-written metric resolves through here, so a spelling
     * taken by {@code CREATE INDEX} is taken by {@code ann_distance} and the reverse.
     */
    public static String normalize(String metric) {
        return metric.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Resolves an alias to its metric under {@link #normalize}.
     *
     * @return the matching metric, or {@code null} if the alias is not recognized.
     */
    public static VectorSimilarityMetric fromAlias(String alias) {
        if (alias == null) {
            return null;
        }
        return BY_ALIAS.get(normalize(alias));
    }
}
