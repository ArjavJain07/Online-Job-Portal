package com.jobportal.repository.projection;

import com.jobportal.domain.enums.Role;

// The small set of columns CurrentUserInterceptor reloads on every request (Section 4.6):
// "load (id, fullName, email, role, enabled, companyName) by id with one small query".
// A closed projection, so Spring Data fills it straight from the SQL row, no full User
// entity or its lazy associations touched.
public interface CurrentUserView {

    Long getId();

    String getFullName();

    String getEmail();

    Role getRole();

    boolean isEnabled();

    String getCompanyName();
}
