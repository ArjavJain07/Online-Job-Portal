package com.jobportal.repository.projection;

import com.jobportal.domain.enums.ApplicationStatus;

// One row of the per-job pivot query "select a.job.id as jobId, a.status as status,
// count(a) as total from JobApplication a ... group by a.job.id, a.status" (Section 7.6,
// E-D5 table), pivoted in Java into JobStatsRow by the employer statistics service.
public interface JobStatusPairCount {

    Long getJobId();

    ApplicationStatus getStatus();

    long getTotal();
}
