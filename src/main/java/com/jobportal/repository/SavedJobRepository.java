package com.jobportal.repository;

import com.jobportal.domain.SavedJob;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedJobRepository extends JpaRepository<SavedJob, Long> {

    // "My saved jobs" (SavedJobService, seeker/saved-jobs.html), newest first - the same
    // "page + rows built once" shape as every other seeker list, with employer eagerly
    // fetched alongside the job so the list template's job-card-style rendering never
    // N+1s (mirrors JobApplicationRepository.findBySeeker_IdAndStatusIn's own EntityGraph).
    @EntityGraph(attributePaths = {"job", "job.employer"})
    Page<SavedJob> findBySeeker_IdOrderBySavedAtDesc(Long seekerId, Pageable pageable);

    // Cheap existence check for the save/unsave button's current state (job list, job
    // detail) - no need to load a whole SavedJob just to know whether one exists.
    boolean existsByJob_IdAndSeeker_Id(Long jobId, Long seekerId);

    // The exact row a save/unsave action acts on: SavedJobService.save() checks this before
    // inserting (idempotent - saving an already-saved job is a no-op, not an error) and
    // unsave() could look the row up the same way, though it deletes directly by the pair
    // instead (see deleteByJob_IdAndSeeker_Id below).
    Optional<SavedJob> findByJob_IdAndSeeker_Id(Long jobId, Long seekerId);

    // Unsave (SavedJobService): deletes the one row for this (seeker, job) pair directly,
    // returning how many rows were removed (0 or 1, thanks to uk_saved_job_seeker_job) so
    // the caller can stay silent about whether it was already gone - unsaving something
    // that was never saved is a harmless no-op, not an error, the same idempotent spirit
    // PasswordResetTokenRepository.deleteByUser_Id already uses for its own cleanup calls.
    long deleteByJob_IdAndSeeker_Id(Long jobId, Long seekerId);

    // Dependency cleanup when a JOB is deleted (JobService.delete, Section 5.8): saved_jobs
    // has no ON DELETE clause (10.7 convention, V5 migration's own comment), so a job some
    // seeker had bookmarked would otherwise block its own deletion with a foreign-key
    // RESTRICT even though 0 applications is the only condition Section 5.8 actually asks
    // for.
    long deleteByJob_Id(Long jobId);

    // Dependency cleanup when a USER is deleted (UserService.delete, Section 5.8): same
    // reasoning as deleteByJob_Id above, mirroring the existing
    // passwordResetTokenRepository.deleteByUser_Id call it sits next to.
    long deleteBySeeker_Id(Long seekerId);
}
