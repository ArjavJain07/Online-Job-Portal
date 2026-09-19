package com.jobportal.domain.enums;

// How an interview actually happens (interview scheduling feature, built on top of the
// existing ApplicationStatus.INTERVIEW stage of Section 5.6). Three constants, chosen to
// cover the whole realistic space with no overlap rather than to be exhaustive: every
// interview is either a voice call, a video call or a visit to a place.
//
// Each constant carries TWO labels, the same two-label shape ApplicationStatus already
// uses for its employer/seeker wording:
//
//   label        - the mode itself ("Video call"), shown in badges and dropdowns.
//   detailLabel  - what the free-text "where" field MEANS for this mode ("Joining link"),
//                  so one nullable Interview.location column can be one stored value and
//                  still be labelled correctly on the employer form, the candidate's page
//                  and inside the email. Without this the field would have to be called
//                  something vague like "Location or link or number" everywhere, or split
//                  into three columns only one of which is ever populated.
//
// detailRequired is the other half of that: a video interview with no joining link and an
// on-site interview with no address are both useless to the candidate, so InterviewForm
// refuses them (its own @AssertTrue), while a phone interview may legitimately say
// nothing - "we will call the number on your profile" is a complete instruction. The rule
// lives here, next to the modes it is about, rather than as a switch inside the form.
public enum InterviewMode {

    PHONE("Phone", "Phone number", false),
    VIDEO("Video call", "Joining link", true),
    ON_SITE("On-site", "Address", true);

    private final String label;
    private final String detailLabel;
    private final boolean detailRequired;

    InterviewMode(String label, String detailLabel, boolean detailRequired) {
        this.label = label;
        this.detailLabel = detailLabel;
        this.detailRequired = detailRequired;
    }

    public String getLabel() {
        return label;
    }

    public String getDetailLabel() {
        return detailLabel;
    }

    public boolean isDetailRequired() {
        return detailRequired;
    }
}
