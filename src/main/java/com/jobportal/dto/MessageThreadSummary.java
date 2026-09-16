package com.jobportal.dto;

import java.time.LocalDateTime;

// One row of an employer or seeker message inbox (Section 6.5.1: "rows = threads with at
// least one message"). "otherPartyName" is the candidate's name on the employer's inbox
// and the company name on the seeker's inbox - whichever side is looking, it is always the
// OTHER party in the thread. "snippet" is the last message's body cut to the first 80
// characters (Section 6.5.1 "last message snippet (first 80 characters)"). Built by
// MessageService.inboxForEmployer/inboxForSeeker, sorted by lastMessageAt, newest first.
public record MessageThreadSummary(Long applicationId, String otherPartyName, String jobTitle, String snippet,
        LocalDateTime lastMessageAt, long unreadCount) {
}
