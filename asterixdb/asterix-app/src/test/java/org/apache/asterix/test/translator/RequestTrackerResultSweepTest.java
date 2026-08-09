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
package org.apache.asterix.test.translator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.asterix.common.api.ICommonRequestParameters;
import org.apache.asterix.common.api.IRequestReference;
import org.apache.asterix.common.config.ExternalProperties;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.runtime.utils.RequestTracker;
import org.apache.asterix.translator.ClientRequest;
import org.apache.hyracks.api.job.JobId;
import org.junit.Test;

/** A request is tracked until none of its results can be fetched any more. */
public class RequestTrackerResultSweepTest {

    private static final JobId JOB_1 = new JobId(1);
    private static final JobId JOB_2 = new JobId(2);
    private static final String REQUEST_ID = "request-uuid";

    @Test
    public void theRequestIsKeptWhileAnotherStatementsResultCanStillBeFetched() {
        RequestTracker tracker = newTracker();
        ClientRequest request = newRequest();
        tracker.trackAsyncOrDeferredRequest(request);
        request.addJob(JOB_1);
        request.addJob(JOB_2);
        request.markResultPending(JOB_1);
        request.markResultPending(JOB_2);

        tracker.notifyResultSweep(JOB_1, REQUEST_ID);
        assertTrue("the second statement's result can still be fetched",
                tracker.getAsyncOrDeferredRequest(REQUEST_ID).isPresent());

        tracker.notifyResultSweep(JOB_2, REQUEST_ID);
        assertFalse(tracker.getAsyncOrDeferredRequest(REQUEST_ID).isPresent());
    }

    @Test
    public void theRequestIsDroppedWhenItsOnlyResultIsSwept() {
        RequestTracker tracker = newTracker();
        ClientRequest request = newRequest();
        tracker.trackAsyncOrDeferredRequest(request);
        request.addJob(JOB_1);
        request.markResultPending(JOB_1);

        tracker.notifyResultSweep(JOB_1, REQUEST_ID);
        assertFalse(tracker.getAsyncOrDeferredRequest(REQUEST_ID).isPresent());
    }

    private static RequestTracker newTracker() {
        ExternalProperties externalProperties = mock(ExternalProperties.class);
        when(externalProperties.getRequestsArchiveSize()).thenReturn(10);
        ICcApplicationContext appCtx = mock(ICcApplicationContext.class);
        when(appCtx.getExternalProperties()).thenReturn(externalProperties);
        return new RequestTracker(appCtx);
    }

    private static ClientRequest newRequest() {
        IRequestReference requestReference = mock(IRequestReference.class);
        when(requestReference.getUuid()).thenReturn(REQUEST_ID);
        ICommonRequestParameters requestParameters = mock(ICommonRequestParameters.class);
        when(requestParameters.getRequestReference()).thenReturn(requestReference);
        when(requestParameters.getStatement()).thenReturn("select 1; select 2;");
        return new ClientRequest(requestParameters);
    }
}
