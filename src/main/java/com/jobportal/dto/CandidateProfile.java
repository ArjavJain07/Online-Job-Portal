package com.jobportal.dto;

import java.util.List;

// Exactly the seeker fields Section 4.5's ownership table lets an employer see on the
// application detail page (email, phone, location, headline, skills, experience,
// education, about), plus the two identity fields the page's header needs (fullName,
// accountDeactivated). Nothing else - in particular, no resume field: the profile resume
// file is never served to an employer, only the copy attached to the application itself
// (Section 6.3 E-F2 business rule, Section 6.5.2). Built by
// JobApplicationService.candidateProfile(...) so the template never touches the seeker's
// User/SeekerProfile entities directly. phone/location/headline/education/about are null
// when the seeker has not filled them in; skills is an empty list, never null.
public record CandidateProfile(String fullName, boolean accountDeactivated, String email, String phone,
        String location, String headline, List<String> skills, int experienceYears, String education,
        String about) {
}
