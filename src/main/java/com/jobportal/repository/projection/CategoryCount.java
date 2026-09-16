package com.jobportal.repository.projection;

import com.jobportal.domain.enums.JobCategory;

// One row of "select j.category as category, count(a) as total from JobApplication a
// join a.job j ... group by j.category" (Section 7.6, applications-by-category chart).
public interface CategoryCount {

    JobCategory getCategory();

    long getTotal();
}
