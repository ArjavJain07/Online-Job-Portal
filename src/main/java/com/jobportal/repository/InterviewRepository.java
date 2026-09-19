package com.jobportal.repository;

import com.jobportal.domain.Interview;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Interviews by the application they belong to (interview scheduling feature). There is
// no findAll-style "upcoming interviews for this employer" query here on purpose: nothing
// in this feature lists interviews as their own collection - every screen reaches an
// interview through an application it is already showing - so adding one would be a query
// with no caller, and Section 3.5's design rules ask for the opposite.
public interface InterviewRepository extends JpaRepository<Interview, Long> {

    // At most one row per application (uk_interview_application, V6), which is what makes
    // this an Optional rather than a List: the uniqueness is enforced by the database, so
    // a second row cannot appear behind this method's back.
    Optional<Interview> findByApplication_Id(Long applicationId);

    // The candidate's tracking list (Section 6.4 S-D2) shows one line per application and
    // needs each one's interview, so it loads them for the whole page in a single query
    // rather than one per row - the same shape JobApplicationService already uses for that
    // page's unread message counts. Returns entities (not a projection) because the
    // template needs Interview#getWhenText and #isUpcoming, both of which are defined on
    // the entity so that every page and every email word an interview identically.
    List<Interview> findByApplication_IdIn(Collection<Long> applicationIds);
}
