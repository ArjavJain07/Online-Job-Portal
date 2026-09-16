package com.jobportal.repository.projection;

import com.jobportal.domain.enums.JobStatus;

// One row of "select j.status as status, count(j) as total from Job j group by j.status"
// (Section 7.6, jobs-by-status chart). Interface projections use the alias "total", not
// the reserved-looking "count".
public interface JobStatusCount {

    JobStatus getStatus();

    long getTotal();
}
