package com.jobportal.repository.projection;

// One row of the "top 5 jobs by applications" query (Section 7.6, admin statistics).
public interface TopJobCount {

    Long getJobId();

    String getTitle();

    String getCompany();

    long getTotal();
}
