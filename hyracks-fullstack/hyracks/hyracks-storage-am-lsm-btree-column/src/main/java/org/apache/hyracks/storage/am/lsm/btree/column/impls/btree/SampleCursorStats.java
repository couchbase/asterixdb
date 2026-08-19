/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.storage.am.lsm.btree.column.impls.btree;

public final class SampleCursorStats {

    public long livenessNanos;
    public long livenessCalls;
    public long livenessKeys;
    public long pkSeekNanos;
    public long pkSeekCalls;
    public long phase2Nanos;
    public long phase2PagePins;
    public long pagesAccepted;
    public long pagesRejected;
    public long attempts;
    /**
     * Page groups visited in phase 1 — one per {@code startSamplingPage()} rewind and forward PK pass, i.e. the
     * count this cursor's cost is actually proportional to. Distinct from {@link #attempts}, which counts
     * <em>draws</em>: a batch is sorted by pageId, so the draws that landed on one page are consumed as a single
     * group sharing one pass. {@code attempts / pageGroups} is therefore the amortization factor achieved.
     */
    public long pageGroups;

    public void reset() {
        livenessNanos = 0;
        livenessCalls = 0;
        livenessKeys = 0;
        pkSeekNanos = 0;
        pkSeekCalls = 0;
        phase2Nanos = 0;
        phase2PagePins = 0;
        pagesAccepted = 0;
        pagesRejected = 0;
        attempts = 0;
        pageGroups = 0;
    }

    public double livenessSharePct() {
        long total = livenessNanos + pkSeekNanos + phase2Nanos;
        return total == 0 ? 0.0 : (100.0 * livenessNanos / total);
    }

    public String format() {
        return String.format(
                "liveness %.1fms (%d calls, %d keys, %.1f%%), pkSeek %.1fms (%d calls), "
                        + "phase2 %.1fms (%d pins), pages %d/%d acc/rej, attempts %d, pageGroups %d",
                livenessNanos / 1e6, livenessCalls, livenessKeys, livenessSharePct(), pkSeekNanos / 1e6, pkSeekCalls,
                phase2Nanos / 1e6, phase2PagePins, pagesAccepted, pagesRejected, attempts, pageGroups);
    }
}
