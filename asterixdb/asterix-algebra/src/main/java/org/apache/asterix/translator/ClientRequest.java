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
package org.apache.asterix.translator;

import static org.apache.hyracks.api.job.resource.IJobCapacityController.JobSubmissionStatus.QUEUE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.asterix.common.api.ICommonRequestParameters;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.om.base.AMutableDateTime;
import org.apache.hyracks.api.client.IHyracksClientConnection;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobStatus;
import org.apache.hyracks.api.job.resource.IJobCapacityController;
import org.apache.hyracks.api.job.resource.IReadOnlyClusterCapacity;
import org.apache.hyracks.api.util.ExceptionUtils;
import org.apache.hyracks.util.LogRedactionUtil;
import org.apache.hyracks.util.StorageUtil;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ClientRequest extends BaseClientRequest {

    protected static final int MAX_STATEMENT_LENGTH =
            StorageUtil.getIntSizeInBytes(64, StorageUtil.StorageUnit.KILOBYTE);
    // what a request that has not created a job yet reports
    private static final JobState EMPTY_JOB_STATE = new JobState();
    protected final long creationTime = System.nanoTime();
    protected final long creationSystemTime = System.currentTimeMillis();
    protected Thread executor;
    protected final String statement;
    protected final String clientContextId;
    /**
     * The jobs of this request, in creation order: one per statement that submits one.
     * <p>
     * Guarded by {@link #jobsLock}, not by the request monitor: {@link #doCancel} holds the monitor while
     * it waits on the job manager to abort a job, and the job manager's own thread reports that job's
     * outcome back here - taking the monitor for that would deadlock.
     */
    private final List<RequestJob> jobs = new ArrayList<>();
    private final Object jobsLock = new Object();
    private volatile long compileTimeNanos;

    public ClientRequest(ICommonRequestParameters requestParameters) {
        super(requestParameters.getRequestReference());
        this.clientContextId = requestParameters.getClientContextId();
        String stmt = requestParameters.getStatement();
        this.statement = stmt.length() > MAX_STATEMENT_LENGTH ? stmt.substring(0, MAX_STATEMENT_LENGTH) : stmt;
        this.executor = Thread.currentThread();
    }

    @Override
    public String getClientContextId() {
        return clientContextId;
    }

    @Override
    public void archived() {
        executor = null;
    }

    public void setPlan(JobId jobId, String plan) {
        if (plan != null) {
            synchronized (jobsLock) {
                jobStateOf(jobId).plan =
                        plan.length() > MAX_STATEMENT_LENGTH ? plan.substring(0, MAX_STATEMENT_LENGTH) : plan;
            }
        }
    }

    /**
     * Adopts the job just submitted for the statement being executed. The job may be known here already: its
     * creation notification is delivered while the submission is still in flight.
     *
     * @return false if the request was cancelled in the meantime, in which case the caller must cancel the job
     *         itself, the cancellation not having been able to reach a job that did not yet exist
     */
    public synchronized boolean addJob(JobId jobId) {
        if (isCancelled()) {
            return false;
        }
        synchronized (jobsLock) {
            // registers the job as well, so that a handle of it is authorised whether or not the creation
            // notification has been delivered yet
            jobStateOf(jobId).compileTimeNanos = compileTimeNanos;
        }
        setRunning();
        return true;
    }

    /** @return the ids of this request's jobs, in creation order */
    public List<JobId> getJobIds() {
        synchronized (jobsLock) {
            return jobs.stream().map(job -> job.jobId).toList();
        }
    }

    /** @return true if the given job is one of this request's */
    public boolean hasJob(JobId jobId) {
        synchronized (jobsLock) {
            return findJob(jobId) != null;
        }
    }

    @Override
    public void markResultPending(JobId jobId) {
        synchronized (jobsLock) {
            jobStateOf(jobId).resultPending = true;
        }
    }

    @Override
    public void resultSwept(JobId jobId) {
        synchronized (jobsLock) {
            RequestJob job = findJob(jobId);
            if (job != null) {
                job.state.resultPending = false;
            }
        }
    }

    @Override
    public boolean hasPendingResults() {
        synchronized (jobsLock) {
            return jobs.stream().anyMatch(job -> job.state.resultPending);
        }
    }

    /**
     * @return the compile time of the statement that submitted the given job, 0 if it is not this request's
     */
    public long getCompileTimeNanos(JobId jobId) {
        synchronized (jobsLock) {
            RequestJob job = findJob(jobId);
            return job != null ? job.state.compileTimeNanos : 0;
        }
    }

    /**
     * @return the jobs that have not reached a terminal state. The statements of a request run one at a time
     *         and each waits for its own job, and a job's outcome is recorded here before that wait is
     *         released, so this is the job of the statement being executed, or nothing between two statements.
     */
    public List<JobId> getUnfinishedJobIds() {
        synchronized (jobsLock) {
            return jobs.stream().filter(job -> !isTerminal(job.state.status)).map(job -> job.jobId).toList();
        }
    }

    /** A job with no status yet has been submitted and its creation not yet notified, so it is not terminal. */
    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.TERMINATED || status == JobStatus.FAILURE
                || status == JobStatus.FAILURE_BEFORE_EXECUTION;
    }

    /** @return the jobs whose results the client was never handed a handle for */
    public List<JobId> getJobsWithoutPendingResults() {
        synchronized (jobsLock) {
            return jobs.stream().filter(job -> !job.state.resultPending).map(job -> job.jobId).toList();
        }
    }

    public Thread getExecutor() {
        return executor;
    }

    @Override
    protected void doCancel(ICcApplicationContext appCtx) throws HyracksDataException {
        // Only the jobs that have not finished are aborted: a finished job's results are what a handle already
        // handed to the client points at, and cancelling it would do nothing in any case. There is at most one
        // such job - see getUnfinishedJobIds - but nothing here relies on that.
        // With no such job nothing would tell the executing thread that the request was cancelled, it being
        // between two statements or compiling one, so the thread is interrupted instead.
        List<JobId> jobIds = getUnfinishedJobIds();
        if (!jobIds.isEmpty()) {
            IHyracksClientConnection hcc = appCtx.getHcc();
            HyracksDataException failure = null;
            for (JobId jobId : jobIds) {
                try {
                    hcc.cancelJob(jobId);
                } catch (Exception e) {
                    if (failure == null) {
                        failure = HyracksDataException.create(e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        } else if (executor != null) {
            executor.interrupt();
        }
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getCreationSystemTime() {
        return creationSystemTime;
    }

    /**
     * Staged here and copied onto the job by {@link #addJob}: a statement compiles before it submits, so
     * the value last staged is the submitting statement's. The compile time is known inside the compiler,
     * where there is no job id yet.
     */
    public void setCompileTimeNanos(long compileTimeNanos) {
        this.compileTimeNanos = compileTimeNanos;
    }

    @Override
    public ObjectNode asJson() {
        ObjectNode json = super.asJson();
        return asJson(json, false);
    }

    @Override
    public ObjectNode asRedactedJson() {
        ObjectNode json = super.asRedactedJson();
        return asJson(json, true);
    }

    private ObjectNode asJson(ObjectNode json, boolean redact) {
        putJobDetails(json, redact);
        json.put("statement", redact ? LogRedactionUtil.statement(statement) : statement);
        json.put("clientContextID", clientContextId);
        // the flat plan describes the first job, as the flat job fields do
        String plan;
        synchronized (jobsLock) {
            plan = jobs.isEmpty() ? null : jobs.getFirst().state.plan;
        }
        if (plan != null) {
            json.put("plan", redact ? LogRedactionUtil.userData(plan) : plan);
        }
        return json;
    }

    @Override
    public void jobCreated(JobId jobId, IReadOnlyClusterCapacity requiredClusterCapacity,
            IJobCapacityController.JobSubmissionStatus status) {
        JobState jobState = jobState(jobId);
        jobState.createTime = System.currentTimeMillis();
        jobState.status = status == QUEUE ? JobStatus.PENDING : JobStatus.RUNNING;
        jobState.requiredCPUs = requiredClusterCapacity.getAggregatedCores();
        jobState.requiredMemoryInBytes = requiredClusterCapacity.getAggregatedMemoryByteSize();
    }

    @Override
    public void jobStarted(JobId jobId) {
        JobState jobState = jobState(jobId);
        jobState.startTime = System.currentTimeMillis();
        jobState.status = JobStatus.RUNNING;
    }

    @Override
    public void jobFinished(JobId jobId, JobStatus jobStatus, List<Exception> exceptions) {
        JobState jobState = jobState(jobId);
        jobState.endTime = System.currentTimeMillis();
        jobState.status = jobStatus;
        if (exceptions != null && !exceptions.isEmpty()) {
            jobState.errorMsg = processException(exceptions.get(0));
        }
    }

    protected String processException(Exception e) {
        return ExceptionUtils.unwrap(e).getMessage();
    }

    private JobState jobState(JobId jobId) {
        synchronized (jobsLock) {
            return jobStateOf(jobId);
        }
    }

    // must be called while holding jobsLock
    private JobState jobStateOf(JobId jobId) {
        RequestJob job = findJob(jobId);
        if (job == null) {
            job = new RequestJob(jobId);
            jobs.add(job);
        }
        return job.state;
    }

    // must be called while holding jobsLock
    private RequestJob findJob(JobId jobId) {
        for (RequestJob job : jobs) {
            if (job.jobId.equals(jobId)) {
                return job;
            }
        }
        return null;
    }

    private void putJobDetails(ObjectNode json, boolean redact) {
        try {
            List<RequestJob> requestJobs;
            synchronized (jobsLock) {
                requestJobs = List.copyOf(jobs);
            }
            // The flat fields describe the first job, so that they - and the flat plan with them - stop
            // changing as the request moves from one statement to the next. What every statement did is in
            // the jobs array, which is where a later statement's status and error are read.
            RequestJob firstJob = requestJobs.isEmpty() ? null : requestJobs.get(0);
            json.put("jobId", firstJob != null ? firstJob.jobId.toString() : null);
            putJobState(json, firstJob != null ? firstJob.state : EMPTY_JOB_STATE, redact);
            if (requestJobs.size() > 1) {
                ArrayNode jobsJson = json.putArray("jobs");
                for (RequestJob job : requestJobs) {
                    ObjectNode jobJson = jobsJson.addObject();
                    jobJson.put("jobId", job.jobId.toString());
                    putJobState(jobJson, job.state, redact);
                    if (job.state.plan != null) {
                        jobJson.put("plan", redact ? LogRedactionUtil.userData(job.state.plan) : job.state.plan);
                    }
                }
            }
        } catch (Throwable th) {
            // ignore
        }
    }

    private static void putJobState(ObjectNode json, JobState state, boolean redact) {
        AMutableDateTime dateTime = new AMutableDateTime(0);
        putTime(json, state.createTime, "jobCreateTime", dateTime);
        putTime(json, state.startTime, "jobStartTime", dateTime);
        putTime(json, state.endTime, "jobEndTime", dateTime);
        long queueTime = 0;
        if (state.createTime > 0) {
            // startTime - createTime, if job has started
            // endTime - createTime, if job has ended but not started (failed while in the queue, cancelled/timeout)
            // currentTime - createTime, if job is still in the queue
            queueTime = (state.startTime > 0 ? state.startTime
                    : (state.endTime > 0 ? state.endTime : System.currentTimeMillis())) - state.createTime;
        }
        json.put("jobQueueTime", TimeUnit.MILLISECONDS.toSeconds(queueTime));
        json.put("jobStatus", String.valueOf(state.status));
        json.put("jobRequiredCPUs", state.requiredCPUs);
        json.put("jobRequiredMemory", state.requiredMemoryInBytes);
        if (state.errorMsg != null) {
            json.put("error", redact ? LogRedactionUtil.userData(state.errorMsg) : state.errorMsg);
        }
    }

    private static void putTime(ObjectNode json, long time, String label, AMutableDateTime dateTime) {
        if (time > 0) {
            dateTime.setValue(time);
            json.put(label, dateTime.toSimpleString());
        }
    }

    static class JobState {
        volatile long createTime;
        volatile long startTime;
        volatile long endTime;
        volatile long requiredMemoryInBytes;
        volatile int requiredCPUs;
        volatile JobStatus status;
        volatile String errorMsg;
        volatile String plan; // can be null
        // kept per job because it is the compile time of that job's statement; not reported in the response
        volatile long compileTimeNanos;
        // the client holds a handle for these results and may still fetch them
        volatile boolean resultPending;
    }

    private static class RequestJob {
        final JobId jobId;
        final JobState state = new JobState();

        RequestJob(JobId jobId) {
            this.jobId = jobId;
        }
    }
}
