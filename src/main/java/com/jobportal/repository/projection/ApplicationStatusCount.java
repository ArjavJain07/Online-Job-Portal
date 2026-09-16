package com.jobportal.repository.projection;

import com.jobportal.domain.enums.ApplicationStatus;

// One row of "select a.status as status, count(a) as total from JobApplication a ...
// group by a.status" (Section 7.6, applications-by-status chart and outcomes pie).
public interface ApplicationStatusCount {

    ApplicationStatus getStatus();

    long getTotal();
}
