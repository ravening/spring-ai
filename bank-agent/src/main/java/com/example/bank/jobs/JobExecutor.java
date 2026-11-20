package com.example.bank.jobs;

import com.example.bank.jobs.JobDtos.JobResult;
import com.example.bank.jobs.JobDtos.JobRow;

/** Strategy for one job type. Validation runs at create/edit time and again at execution. */
public interface JobExecutor {

    String type();

    /** Throws ResponseStatusException on invalid payload. */
    void validate(String payload, String userId);

    JobResult execute(JobRow job);
}
