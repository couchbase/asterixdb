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
package org.apache.hyracks.storage.am.vector.utils;

import java.io.Serializable;

/**
 * Immutable cross-pollination parameters threaded from the index DDL (WITH clause) down to the
 * storage-layer {@code VTree}, so that incremental insert/delete reproduce the exact multi-cluster
 * placement that bulk-load performs.
 * <p>
 * At bulk-load a record is written into the {@code m} closest leaf centroids, after eps-filtering
 * ({@code epsilon}) the candidate list and thinning it by the SPTAG-style RNG diversity rule
 * ({@code rngFactor}; see {@link RngAcceptanceFilter}). For incremental delete to cancel <em>every</em>
 * replica, the DML path must resolve the same centroid set — which means it must use the same three
 * parameters.
 * <p>
 * {@code m == 1} is plain single-closest placement, but it does <em>not</em> bypass the level-wise
 * search: {@code VTree#findReplicaClusters} runs the same
 * {@code findCloseCentroidsLevelWiseGlobalSort} + {@link RngAcceptanceFilter} pass for every {@code m},
 * and at {@code m == 1} simply takes the closest of the candidates that pass. {@code epsilon} prunes
 * that descent at every interior level, so a wider window can surface a strictly closer centroid and
 * change which single cluster wins. <em>{@code epsilon} is therefore load-bearing at every {@code m},
 * and must be identical on the bulk-load and DML paths.</em>
 * <p>
 * There is deliberately <em>no</em> default instance and no default for any component. Every holder
 * requires a non-null config and every persisted resource carries all three values explicitly, so the
 * only source is the index's own DDL. A fallback default here would be a second source of truth for
 * a value that must agree across bulk-load, insert and delete: the two silently diverging is what
 * previously let a delete resolve a different leaf cluster than the matter it had to cancel, leaving
 * the deleted record visible to vector search. Do not reintroduce one — pass the config through.
 *
 * @param m         replica count; must be >= 1, and a smaller value is rejected rather than clamped.
 *                  1 disables cross-pollination.
 * @param rngFactor RNG diversity multiplier (canonical SPTAG = 1.0; non-finite disables the rule).
 *                  Inert at {@code m == 1}, where the diversity test never runs.
 * @param epsilon   level-wise candidate window used to gather centroids before RNG thinning. Applies
 *                  at every {@code m}, including 1.
 */
public record CrossPollinationConfig(int m, double rngFactor, double epsilon) implements Serializable {

    private static final long serialVersionUID = 1L;

    public CrossPollinationConfig {
        // m is the replica count. DDL validates it to [1, MAX_CROSS_POLLINATION_M] and it defaults to 1,
        // so a value below 1 is a wiring bug or a corrupt resource, not something to correct. Clamping it
        // silently would produce a placement that disagrees with whatever the other path resolved, which
        // is the same class of failure as substituting a default for epsilon.
        if (m < 1) {
            throw new IllegalArgumentException("m must be >= 1, got " + m);
        }
        // epsilon is a multiplicative window width; a negative value is meaningless (it would
        // shrink rather than widen the candidate window). Reject it up front.
        if (epsilon < 0.0) {
            throw new IllegalArgumentException("epsilon must be >= 0, got " + epsilon);
        }
        // rngFactor is intentionally NOT validated: a non-finite value (NaN / +Infinity) is a
        // legal input that disables the RNG diversity rule in RngAcceptanceFilter (degrading to a
        // pure top-cap slice). Its semantics are the caller's contract.
    }


    /** @return true when records are replicated into more than one cluster. */
    public boolean enabled() {
        return m > 1;
    }
}
