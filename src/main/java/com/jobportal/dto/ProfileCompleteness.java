package com.jobportal.dto;

// Seeker profile completeness bar (Section 6.4 S-F4/S-D3 "Completeness bar"): the weights
// (resume 30, skills 20, headline 10, location 10, phone 10, education 10, about 10, sum
// 100) add up to percent, and hint is the sentence for the first missing item in that same
// weight order, or null once percent is 100 (the bar is hidden then, Section 6.4 DASH-S).
// Built by SeekerProfileService.completeness(...) so seeker/profile.html only displays a
// ready-made value.
public record ProfileCompleteness(int percent, String hint) {
}
