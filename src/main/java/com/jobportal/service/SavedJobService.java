package com.jobportal.service;

import com.jobportal.domain.Job;
import com.jobportal.domain.SavedJob;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.SavedJobRepository;
import com.jobportal.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Save/unsave a job and list a seeker's saved jobs (Section 16 future-work item 5). The
// whole feature reduces to one small idempotent table (SavedJob's own comment explains why
// it is a table of its own), so this service stays deliberately thin - no history, no
// status, just "does this (seeker, job) row exist right now".
//
// REUSE, NOT REBUILD: job visibility is already one definition (Section 3.5 rule 3/4) -
// JobSearchService.findForDetail already knows exactly which jobs a given viewer is allowed
// to see (Live to anyone, pending/rejected previewed only by their own employer or an
// admin). save() calls that instead of re-deriving "can this seeker even see this job" from
// scratch, so a seeker can never bookmark a job whose detail page would 404 for them.
@Service
public class SavedJobService {

    private final SavedJobRepository savedJobRepository;
    private final UserRepository userRepository;
    private final JobSearchService jobSearchService;
    private final SettingsService settingsService;
    private final Clock clock;

    public SavedJobService(SavedJobRepository savedJobRepository, UserRepository userRepository,
            JobSearchService jobSearchService, SettingsService settingsService, Clock clock) {
        this.savedJobRepository = savedJobRepository;
        this.userRepository = userRepository;
        this.jobSearchService = jobSearchService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    // Whether this seeker has this job saved right now - the save/unsave button's current
    // state on the job list pane and the job detail page.
    @Transactional(readOnly = true)
    public boolean isSaved(Long seekerId, Long jobId) {
        return savedJobRepository.existsByJob_IdAndSeeker_Id(jobId, seekerId);
    }

    // Saves a job for a seeker. Idempotent by design: a double submission (a stale page, or
    // the same click landing twice) simply finds the row already there and does nothing
    // more - the unique constraint (uk_saved_job_seeker_job) is the backstop against a
    // genuine race between two concurrent requests, which GlobalExceptionHandler's existing
    // DataIntegrityViolationException handler already turns into a safe, if generic, "could
    // not be saved" flash rather than a 500 (the same backstop JobApplicationService.apply()
    // leans on more explicitly for its own, much more likely, duplicate-submit race - here
    // the window is a single unconfirmed click rather than a multi-field form round trip,
    // so that extra machinery was judged not worth the duplication).
    //
    // findForDetail(jobId, seekerId, JOB_SEEKER) both loads the job (throwing
    // ResourceNotFoundException for an unknown id, a plain 404) and enforces the same
    // visibility rule the standalone job detail page already uses, so a hand-crafted POST
    // naming a pending-approval or rejected job's id 404s here exactly as it would on
    // GET /jobs/{id}.
    @Transactional
    public void save(Long seekerId, Long jobId) {
        if (savedJobRepository.existsByJob_IdAndSeeker_Id(jobId, seekerId)) {
            return;
        }
        Job job = jobSearchService.findForDetail(jobId, seekerId, Role.JOB_SEEKER);
        User seeker = userRepository.findById(seekerId)
                .orElseThrow(() -> new ResourceNotFoundException("Seeker " + seekerId + " does not exist"));

        SavedJob savedJob = new SavedJob();
        savedJob.setSeeker(seeker);
        savedJob.setJob(job);
        savedJob.setSavedAt(LocalDateTime.now(clock));
        savedJobRepository.save(savedJob);
    }

    // Unsaves a job. Also idempotent: unsaving a job that was never saved (or was already
    // removed by another tab) changes nothing and is still reported to the caller as
    // success - there is no meaningful difference, from the seeker's side, between "it's
    // gone" and "it was already gone".
    @Transactional
    public void unsave(Long seekerId, Long jobId) {
        savedJobRepository.deleteByJob_IdAndSeeker_Id(jobId, seekerId);
    }

    // "My saved jobs" (paginated like every other list of more-than-a-handful in this app,
    // Section 7.9's pageSize setting), newest save first. `rawPage` is a lenient, unparsed
    // query parameter - the controller hands it straight through and the parsing happens
    // here, the same split JobSearchService.search()/normalise() and
    // JobApplicationService.applicationHistoryForSeeker() already use: an absent,
    // non-numeric or negative value is simply page 0, never a 400.
    @Transactional(readOnly = true)
    public Page<SavedJob> list(Long seekerId, String rawPage) {
        Pageable pageable = PageRequest.of(parsePage(rawPage), settingsService.get().getPageSize(), Sort.unsorted());
        return savedJobRepository.findBySeeker_IdOrderBySavedAtDesc(seekerId, pageable);
    }

    private int parsePage(String rawPage) {
        try {
            return Math.max(Integer.parseInt(rawPage), 0);
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }
}
