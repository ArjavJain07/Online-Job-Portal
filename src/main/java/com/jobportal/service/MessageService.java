package com.jobportal.service;

import com.jobportal.domain.JobApplication;
import com.jobportal.domain.Message;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.dto.MessageThreadSummary;
import com.jobportal.exception.BusinessRuleException;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.projection.ApplicationIdCount;
import com.jobportal.web.form.MessageForm;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Messaging between an employer and the candidates who applied to their jobs (Section
// 6.5.1, decision D-13): one thread per JobApplication, the employer always starts it, the
// seeker may only reply once the employer has sent at least one message. Every public
// method here is role-suffixed (For Employer / For Seeker), the same convention
// JobApplicationService uses, because the two sides see a different "other party" (the
// candidate vs. the company) and are bound by a different set of rules.
@Service
public class MessageService {

    // Section 6.5.1 "Blocked when" / "Check order": these three exact sentences, checked
    // in this order - account deactivated, then application withdrawn, then (seeker only)
    // no employer message yet.
    private static final String DEACTIVATED_MESSAGE = "Messaging is unavailable because this account is deactivated.";
    private static final String WITHDRAWN_MESSAGE = "Messaging is closed because this application was withdrawn.";
    private static final String WAIT_FOR_EMPLOYER_MESSAGE = "You can reply once the employer has messaged you.";

    // Section 6.5.1 employer inbox / E-D3: "last message snippet (first 80 characters)".
    private static final int SNIPPET_LENGTH = 80;

    private final JobApplicationRepository jobApplicationRepository;
    private final MessageRepository messageRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    public MessageService(JobApplicationRepository jobApplicationRepository, MessageRepository messageRepository,
            ActivityLogService activityLogService, Clock clock) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.messageRepository = messageRepository;
        this.activityLogService = activityLogService;
        this.clock = clock;
    }

    // ==================== Inbox (Section 6.5.1 "Employer inbox" / "Seeker inbox") ====================

    // One row per own application that has at least one message, newest last message
    // first. MessageRepository has no single query that returns "applications with a
    // thread plus their last message" (it only has the per-application UNREAD count as
    // one GROUP BY query, Section 6.5.1 "Unread counts" - reused below), so this walks the
    // employer's own applications (already eager-fetched with job+seeker by
    // findForEmployer's @EntityGraph) and loads each one's messages to find the latest.
    // That is one extra query per thread, which is fine at this application's scale (an
    // employer's own application count, never the whole messages table) and keeps the
    // query surface this slice needs limited to what MessageRepository already exposes.
    public List<MessageThreadSummary> inboxForEmployer(Long employerId) {
        Map<Long, Long> unread = unreadCountsByApplication(employerId);
        List<JobApplication> applications = jobApplicationRepository
                .findForEmployer(employerId, null, EnumSet.allOf(ApplicationStatus.class), Pageable.unpaged())
                .getContent();
        List<MessageThreadSummary> rows = new ArrayList<>();
        for (JobApplication application : applications) {
            Message last = lastMessage(application.getId());
            if (last == null) {
                continue; // E-D3: "rows = threads with at least one message"
            }
            rows.add(new MessageThreadSummary(application.getId(), application.getSeeker().getFullName(),
                    application.getJob().getTitle(), snippet(last.getBody()), last.getSentAt(),
                    unread.getOrDefault(application.getId(), 0L)));
        }
        rows.sort(Comparator.comparing(MessageThreadSummary::lastMessageAt).reversed());
        return rows;
    }

    // Same shape as inboxForEmployer, but the "other party" is the job's company, not a
    // person (Section 6.5.1 "Seeker inbox": "Company · Job · snippet · time · unread").
    public List<MessageThreadSummary> inboxForSeeker(Long seekerId) {
        Map<Long, Long> unread = unreadCountsByApplication(seekerId);
        List<JobApplication> applications = jobApplicationRepository.findBySeeker_IdAndStatusIn(seekerId,
                EnumSet.allOf(ApplicationStatus.class), Sort.unsorted());
        List<MessageThreadSummary> rows = new ArrayList<>();
        for (JobApplication application : applications) {
            Message last = lastMessage(application.getId());
            if (last == null) {
                continue;
            }
            rows.add(new MessageThreadSummary(application.getId(), application.getJob().getEmployer().getCompanyName(),
                    application.getJob().getTitle(), snippet(last.getBody()), last.getSentAt(),
                    unread.getOrDefault(application.getId(), 0L)));
        }
        rows.sort(Comparator.comparing(MessageThreadSummary::lastMessageAt).reversed());
        return rows;
    }

    private Message lastMessage(Long applicationId) {
        List<Message> messages = messageRepository.findByApplication_IdOrderBySentAtAsc(applicationId);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    // Section 6.5.1 "last message snippet (first 80 characters)" - the plain first 80
    // characters, with nothing appended, so this matches that wording exactly.
    private String snippet(String body) {
        return body.length() <= SNIPPET_LENGTH ? body : body.substring(0, SNIPPET_LENGTH);
    }

    // Section 6.5.1 "Unread counts": "per-thread counts in inboxes and application lists
    // (one GROUP BY query)" - MessageRepository.countUnreadByApplication already IS that
    // one query; this only reshapes its rows into a Map the way
    // JobApplicationService.unreadCountsByApplication does for the employer/seeker
    // application lists.
    private Map<Long, Long> unreadCountsByApplication(Long userId) {
        Map<Long, Long> counts = new HashMap<>();
        for (ApplicationIdCount row : messageRepository.countUnreadByApplication(userId)) {
            counts.put(row.getApplicationId(), row.getTotal());
        }
        return counts;
    }

    // ==================== Compose (employer only, Section 6.5.1 "Compose page") ====================

    // The compose select's options: every one of the employer's own applications except
    // withdrawn ones and deactivated candidates.
    public List<JobApplication> composableApplicationsForEmployer(Long employerId) {
        List<JobApplication> applications = jobApplicationRepository
                .findForEmployer(employerId, null, EnumSet.allOf(ApplicationStatus.class), Pageable.unpaged())
                .getContent();
        List<JobApplication> eligible = new ArrayList<>();
        for (JobApplication application : applications) {
            if (application.getStatus() != ApplicationStatus.WITHDRAWN && application.getSeeker().isEnabled()) {
                eligible.add(application);
            }
        }
        eligible.sort(Comparator.comparing(JobApplication::getAppliedAt).reversed());
        return eligible;
    }

    // Section 6.5.1: "?applicationId= ... is lenient like every other query parameter
    // (7.9): bound as String, and a blank, non-numeric or foreign value simply pre-selects
    // nothing." Ownership alone decides "foreign" here, the same way
    // JobApplicationService's own ownJobIdOrNull does for the employer applications filter
    // - a withdrawn or deactivated-candidate application that is still the employer's own
    // is left to pre-select (it will not appear in "applications" for the select to match
    // anyway, so the select simply shows its blank option).
    public Long preselectedApplicationId(Long employerId, String rawApplicationId) {
        if (rawApplicationId == null || rawApplicationId.isBlank()) {
            return null;
        }
        Long applicationId;
        try {
            applicationId = Long.valueOf(rawApplicationId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return jobApplicationRepository.findByIdAndJob_Employer_Id(applicationId, employerId).isPresent()
                ? applicationId : null;
    }

    // ==================== Opening a thread (Section 6.5.1 "Read receipts", 7.10) ====================

    // Loads the owned application, marks every message THIS employer has received on it
    // as read (Section 7.10: this bulk update must run before the messages below are
    // loaded, so a message that just became unread already shows as read on this same
    // response) and returns everything the thread view needs: the messages oldest first,
    // who the "other party" is, and whether/why replying is blocked.
    @Transactional
    public ThreadView openThreadForEmployer(Long applicationId, Long employerId) {
        JobApplication application = jobApplicationRepository.findByIdAndJob_Employer_Id(applicationId, employerId)
                .orElseThrow(() -> new ResourceNotFoundException("Application " + applicationId + " does not exist"));
        messageRepository.markThreadRead(applicationId, employerId, LocalDateTime.now(clock));
        List<Message> messages = messageRepository.findByApplication_IdOrderBySentAtAsc(applicationId);
        return new ThreadView(application, messages, blockedReasonForEmployer(application));
    }

    // Same as openThreadForEmployer, from the seeker's side: ownership is
    // findByIdAndSeeker_Id instead.
    @Transactional
    public ThreadView openThreadForSeeker(Long applicationId, Long seekerId) {
        JobApplication application = jobApplicationRepository.findByIdAndSeeker_Id(applicationId, seekerId)
                .orElseThrow(() -> new ResourceNotFoundException("Application " + applicationId + " does not exist"));
        messageRepository.markThreadRead(applicationId, seekerId, LocalDateTime.now(clock));
        List<Message> messages = messageRepository.findByApplication_IdOrderBySentAtAsc(applicationId);
        return new ThreadView(application, messages, blockedReasonForSeeker(application, messages));
    }

    // Everything fragments/message-thread and the four pages that embed it need for one
    // thread: the application (ownership already checked, and its own job/seeker
    // associations already give a caller the "other party" to display - see
    // employer/message-thread.html and seeker/message-thread.html), its messages oldest
    // first, and - when replying is blocked - the exact sentence to show instead of the
    // composer (null means the composer may show).
    public record ThreadView(JobApplication application, List<Message> messages, String blockedReason) {
    }

    // ==================== Blocking rules (Section 6.5.1 "Blocked when" / "Check order") ====================

    private String commonBlockedReason(JobApplication application) {
        if (!application.getJob().getEmployer().isEnabled() || !application.getSeeker().isEnabled()) {
            return DEACTIVATED_MESSAGE;
        }
        if (application.getStatus() == ApplicationStatus.WITHDRAWN) {
            return WITHDRAWN_MESSAGE;
        }
        return null;
    }

    // The employer may always send the first message (D-13), so only the two rules that
    // apply to either side matter here.
    public String blockedReasonForEmployer(JobApplication application) {
        return commonBlockedReason(application);
    }

    // Adds the seeker-only third rule: the thread's messages (already loaded by whichever
    // caller has them - openThreadForSeeker, sendFromSeeker) tell whether the employer has
    // sent at least one.
    public String blockedReasonForSeeker(JobApplication application, List<Message> threadMessages) {
        String common = commonBlockedReason(application);
        if (common != null) {
            return common;
        }
        Long employerId = application.getJob().getEmployer().getId();
        boolean employerHasMessaged = threadMessages.stream()
                .anyMatch(message -> message.getSender().getId().equals(employerId));
        return employerHasMessaged ? null : WAIT_FOR_EMPLOYER_MESSAGE;
    }

    // ==================== Sending (Section 6.5.1, 5.7 MESSAGE_SENT) ====================

    // AC-E-F3-2: an application id that is not this employer's own gives 404 (the same
    // ownership-finder rule as everywhere else, Section 4.5); a blocked thread is refused
    // with a BusinessRuleException carrying the exact blocking sentence, left uncaught the
    // same way EmployerApplicationController#changeStatus leaves an invalid status
    // transition uncaught (Section 6.3 E-F2) - GlobalExceptionHandler turns it into the
    // "error" flash and redirects back to the page the form was on.
    @Transactional
    public JobApplication sendFromEmployer(Long applicationId, Long employerId, MessageForm form) {
        JobApplication application = jobApplicationRepository.findByIdAndJob_Employer_Id(applicationId, employerId)
                .orElseThrow(() -> new ResourceNotFoundException("Application " + applicationId + " does not exist"));
        String blocked = blockedReasonForEmployer(application);
        if (blocked != null) {
            throw new BusinessRuleException(blocked);
        }
        User employer = application.getJob().getEmployer();
        User seeker = application.getSeeker();
        saveMessage(application, employer, seeker, form.getBody());

        // Section 5.7: the description names the company, never the body, matching the
        // catalogue's own example exactly.
        String description = employer.getCompanyName() + " messaged a candidate (" + application.getReference() + ")";
        activityLogService.log(ActivityType.MESSAGE_SENT, employer, description, TargetType.APPLICATION, applicationId);
        return application;
    }

    // Same shape from the seeker's side; the block check needs the thread's existing
    // messages (the "seeker before first employer message" rule), so it is loaded here
    // rather than reused from a caller that may not have it.
    @Transactional
    public JobApplication sendFromSeeker(Long applicationId, Long seekerId, MessageForm form) {
        JobApplication application = jobApplicationRepository.findByIdAndSeeker_Id(applicationId, seekerId)
                .orElseThrow(() -> new ResourceNotFoundException("Application " + applicationId + " does not exist"));
        List<Message> existing = messageRepository.findByApplication_IdOrderBySentAtAsc(applicationId);
        String blocked = blockedReasonForSeeker(application, existing);
        if (blocked != null) {
            throw new BusinessRuleException(blocked);
        }
        User seeker = application.getSeeker();
        User employer = application.getJob().getEmployer();
        saveMessage(application, seeker, employer, form.getBody());

        String description = seeker.getFullName() + " messaged " + employer.getCompanyName() + " ("
                + application.getReference() + ")";
        activityLogService.log(ActivityType.MESSAGE_SENT, seeker, description, TargetType.APPLICATION, applicationId);
        return application;
    }

    private void saveMessage(JobApplication application, User sender, User recipient, String body) {
        Message message = new Message();
        message.setApplication(application);
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setBody(body);
        message.setSentAt(LocalDateTime.now(clock));
        messageRepository.save(message);
    }

    // ==================== Unread badge (Section 6.5.1 "Unread counts" / "Badge on a page ...") ====================

    // Navbar envelope total for one user. Public (unlike the private per-application map
    // above) because the four pages that call markThreadRead must recount and OVERWRITE
    // the "unreadMessageCount" model attribute with this after their bulk update runs -
    // GlobalModelAttributes already computed it once, before the handler ran, and that
    // value is now stale (Section 6.5.1 "Badge on a page that marks messages read").
    public long unreadCount(Long userId) {
        return messageRepository.countByRecipient_IdAndReadAtIsNull(userId);
    }
}
