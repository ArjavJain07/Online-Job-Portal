package com.jobportal.web.common;

import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.Role;
import com.jobportal.dto.JobSearchResult;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.JobSearchService;
import com.jobportal.service.SavedJobService;
import com.jobportal.web.form.JobSearchCriteria;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

// Public job search and job detail (Section 6.1 P-2, 6.4 S-F1, 6.6 route summary): the
// routes anyone can open, logged in or not. All querying (filtering, visibility, view
// counting) lives in JobSearchService (Section 3.1: controllers stay thin); this class
// only turns the result into model attributes the templates can render.
@Controller
public class JobBrowseController {

    private static final DateTimeFormatter DEADLINE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final JobSearchService jobSearchService;
    private final SavedJobService savedJobService;
    private final Clock clock;

    public JobBrowseController(JobSearchService jobSearchService, SavedJobService savedJobService, Clock clock) {
        this.jobSearchService = jobSearchService;
        this.savedJobService = savedJobService;
        this.clock = clock;
    }

    // GET /jobs: the public search list (S-F1). Anonymous, seeker, employer and admin all
    // see the same page here - only /seeker/jobs (built in M5) adds "Applied" badges, so
    // job-card is always called with applied = false from this controller.
    // "job" (added for the two-pane layout, Section 7.1 core rule 7) is a new, optional
    // request parameter: the id of the job to open in the right-hand ".jp-jobs-detail"
    // pane. It never affects search() itself - JobSearchService.selectForPane just picks
    // which of this page's own results to show, falling back to the first one when "job"
    // is absent or belongs to a different page/filter combination.
    @GetMapping("/jobs")
    public String search(JobSearchCriteria criteria, @RequestParam(name = "job", required = false) Long jobId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        JobSearchResult result = jobSearchService.search(criteria);
        model.addAttribute("criteria", criteria);
        model.addAttribute("page", result.jobs());
        model.addAttribute("warning", firstWarningOrNull(result));
        // Section 10.8: the "Java (24)" chips fragments/job-filters renders.
        model.addAttribute("skillFacets", result.skillFacets());

        Job selectedJob = jobSearchService.selectForPane(result.jobs().getContent(), jobId);
        model.addAttribute("selectedJob", selectedJob);
        model.addAttribute("selectedJobId", selectedJob == null ? null : selectedJob.getId());
        // Same "who can apply" question the standalone detail page answers, but only for
        // whichever job the pane is currently showing - null for anyone but a job seeker,
        // and null for a seeker who has not applied yet (see the pane's apply/login CTA).
        Long viewerId = me == null ? null : me.getId();
        Role viewerRole = me == null ? null : me.getRole();
        model.addAttribute("existingApplication", selectedJob == null ? null
                : jobSearchService.findSeekerApplication(selectedJob, viewerId, viewerRole).orElse(null));
        return "public/jobs";
    }

    // GET /jobs/{id}: the public job detail page (P-2). Visibility, the preview/closed
    // banners and the apply panel all depend on who is looking, so that is worked out once
    // here and handed to the template as plain model attributes - no T(...) references to
    // application types in the template (see the note on Thymeleaf 3.1's restricted mode).
    @GetMapping("/jobs/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserDetails me, HttpSession session,
            @RequestHeader(value = "User-Agent", required = false) String userAgent, Model model) {
        Long viewerId = me == null ? null : me.getId();
        Role viewerRole = me == null ? null : me.getRole();

        Job job = jobSearchService.findForDetail(id, viewerId, viewerRole);
        jobSearchService.recordView(job, session, viewerId, viewerRole, userAgent);

        LocalDate today = LocalDate.now(clock);
        boolean live = job.isLive(today);
        boolean anonymousOrSeeker = me == null || viewerRole == Role.JOB_SEEKER;
        Optional<JobApplication> existingApplication = jobSearchService.findSeekerApplication(job, viewerId, viewerRole);

        model.addAttribute("job", job);
        model.addAttribute("deadlineInfo", deadlineInfo(job, today));
        model.addAttribute("banner", banner(job, today, viewerId, viewerRole));
        model.addAttribute("showApplyPanel", live && anonymousOrSeeker);
        model.addAttribute("existingApplication", existingApplication.orElse(null));
        // Save/unsave button (Section 16 future-work item 5): null for anyone but a job
        // seeker - including an anonymous visitor, who has nowhere to save a job TO - the
        // same "null means not applicable to this viewer" convention "existingApplication"
        // already uses above. Deliberately independent of showApplyPanel/live: a seeker can
        // still want to un-save a job here after it has closed or expired, not only while
        // it is open for applications.
        model.addAttribute("saved", viewerRole == Role.JOB_SEEKER ? savedJobService.isSaved(viewerId, job.getId()) : null);
        return "public/job-detail";
    }

    private String firstWarningOrNull(JobSearchResult result) {
        return result.warnings().isEmpty() ? null : result.warnings().get(0);
    }

    // "Apply by 11 Oct 2026, 25 days left" (P-2 detail screen). The plan gives no wording
    // for a deadline that has already passed or falls today, so a job seen that way (only
    // an owning employer or an admin previewing a non-Live job ever sees this) gets the
    // simplest reasonable text instead of a negative or zero day count.
    private String deadlineInfo(Job job, LocalDate today) {
        long daysLeft = ChronoUnit.DAYS.between(today, job.getApplicationDeadline());
        String date = job.getApplicationDeadline().format(DEADLINE_DATE_FORMAT);
        String daysText;
        if (daysLeft > 0) {
            daysText = daysLeft + " days left";
        } else if (daysLeft == 0) {
            daysText = "last day to apply";
        } else {
            daysText = "deadline passed";
        }
        return "Apply by " + date + ", " + daysText;
    }

    // The banner shown above (Live, seen by its own employer) or instead of (every
    // non-Live case) the apply panel - the "who can see which job" table in Section 6.1
    // P-2. Returns null when the page needs no banner at all: a Live job seen by anyone
    // except its own employer.
    private DetailBanner banner(Job job, LocalDate today, Long viewerId, Role viewerRole) {
        boolean admin = viewerRole == Role.ADMIN;
        boolean owner = viewerRole == Role.EMPLOYER && job.getEmployer().getId().equals(viewerId);

        if (!job.getEmployer().isEnabled()) {
            return new DetailBanner("Hidden (employer deactivated)", "secondary", false);
        }
        if (job.getStatus() == JobStatus.PENDING_APPROVAL) {
            return new DetailBanner("Preview: Pending approval", "warning", admin);
        }
        if (job.getStatus() == JobStatus.REJECTED) {
            return new DetailBanner("Preview: Rejected. Reason: " + job.getRejectionReason(), "warning", admin);
        }
        if (!job.isLive(today)) {
            return new DetailBanner("This job is no longer accepting applications.", "secondary", false);
        }
        if (owner) {
            return new DetailBanner("This is how job seekers see your job.", "info", false);
        }
        return null;
    }

    // Banner text plus its Bootstrap alert colour and whether to add the admin's "Review"
    // button, so the template only has one null check instead of several attributes that
    // would otherwise have to agree with each other.
    public record DetailBanner(String text, String style, boolean showReviewButton) {
    }
}
