package com.jobportal.repository.projection;

import java.time.LocalDateTime;

// One row of MessageRepository.findMessageEvents (Section 7.6, E-D5 reply rate and
// messages-over-time chart). Grouped and split by sender in Java, not SQL.
public interface MessageEventRow {

    Long getApplicationId();

    Long getSenderId();

    Long getRecipientId();

    LocalDateTime getSentAt();
}
