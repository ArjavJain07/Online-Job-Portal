package com.jobportal.repository.projection;

import java.time.LocalDateTime;

// One row of the "first employer response" query (Section 7.6, E-D5 first-response
// metric): the application's appliedAt and the earliest status change the employer made.
// The number of days between the two is computed in Java, not SQL.
public interface FirstResponseRow {

    Long getApplicationId();

    LocalDateTime getAppliedAt();

    LocalDateTime getFirstChange();
}
