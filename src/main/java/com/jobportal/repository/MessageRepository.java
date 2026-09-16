package com.jobportal.repository;

import com.jobportal.domain.Message;
import com.jobportal.repository.projection.ApplicationIdCount;
import com.jobportal.repository.projection.MessageEventRow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // The thread's messages, oldest first (loaded only after markThreadRead has run,
    // Section 7.10).
    List<Message> findByApplication_IdOrderBySentAtAsc(Long applicationId);

    // Navbar envelope badge (7.1).
    long countByRecipient_IdAndReadAtIsNull(Long recipientId);

    // Messages sent statistic (7.6).
    long countBySentAtGreaterThanEqual(LocalDateTime from);

    // Dependency check (Section 5.8): messages sent or received by a user.
    long countBySender_IdOrRecipient_Id(Long senderId, Long recipientId);

    // Read receipts (6.5.3, 7.10): plain @Modifying, no clearAutomatically, run before
    // the thread's messages are loaded for the page.
    @Modifying
    @Query("update Message m set m.readAt = :now where m.application.id = :applicationId "
            + "and m.recipient.id = :recipientId and m.readAt is null")
    void markThreadRead(@Param("applicationId") Long applicationId, @Param("recipientId") Long recipientId,
            @Param("now") LocalDateTime now);

    // Per-thread unread counts for inboxes and application lists (7.6), collected into a
    // Map<Long, Long> keyed by applicationId.
    @Query("select m.application.id as applicationId, count(m) as total from Message m "
            + "where m.recipient.id = :userId and m.readAt is null group by m.application.id")
    List<ApplicationIdCount> countUnreadByApplication(@Param("userId") Long userId);

    // Reply rate, candidates messaged and the messages chart (E-D5, 7.6); jobId optional.
    @Query("select m.application.id as applicationId, m.sender.id as senderId, "
            + "m.recipient.id as recipientId, m.sentAt as sentAt from Message m "
            + "where m.application.job.employer.id = :employerId and (:jobId is null or m.application.job.id = :jobId) "
            + "order by m.sentAt")
    List<MessageEventRow> findMessageEvents(@Param("employerId") Long employerId, @Param("jobId") Long jobId);
}
