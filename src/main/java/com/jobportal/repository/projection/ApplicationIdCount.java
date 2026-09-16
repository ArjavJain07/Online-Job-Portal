package com.jobportal.repository.projection;

// One row of "select m.application.id as applicationId, count(m) as total from Message m
// ... group by m.application.id" (Section 7.6, unread-per-thread query). Callers collect
// these into a Map<Long, Long> keyed by applicationId.
public interface ApplicationIdCount {

    Long getApplicationId();

    long getTotal();
}
