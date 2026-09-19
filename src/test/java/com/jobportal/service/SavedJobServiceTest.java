package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobportal.domain.Job;
import com.jobportal.domain.SavedJob;
import com.jobportal.domain.SystemSettings;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.SavedJobRepository;
import com.jobportal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// Unit tests for SavedJobService, built by hand with every collaborator mocked (Section
// 12.1) - the same shape JobAlertServiceTest/PasswordResetServiceTest use. JobSearchService
// is mocked rather than real here specifically to prove SavedJobService REUSES its existing
// visibility rule (findForDetail) instead of re-deriving "can this seeker see this job" -
// see save()'s own comment. The real, wired-together behaviour (an actual save/unsave
// round trip through the database and the HTTP routes) is SavedJobFlowTest instead.
class SavedJobServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW_INSTANT = Instant.parse("2026-09-16T04:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW_INSTANT, ZONE);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private SavedJobRepository savedJobRepository;
    private UserRepository userRepository;
    private JobSearchService jobSearchService;
    private SettingsService settingsService;
    private SavedJobService service;

    @BeforeEach
    void setUp() {
        savedJobRepository = mock(SavedJobRepository.class);
        userRepository = mock(UserRepository.class);
        jobSearchService = mock(JobSearchService.class);
        settingsService = mock(SettingsService.class);
        service = new SavedJobService(savedJobRepository, userRepository, jobSearchService, settingsService, CLOCK);
    }

    @Test
    void isSavedDelegatesToTheRepository() {
        when(savedJobRepository.existsByJob_IdAndSeeker_Id(5L, 42L)).thenReturn(true);
        assertThat(service.isSaved(42L, 5L)).isTrue();
        when(savedJobRepository.existsByJob_IdAndSeeker_Id(5L, 42L)).thenReturn(false);
        assertThat(service.isSaved(42L, 5L)).isFalse();
    }

    @Test
    void saveIsANoOpWhenAlreadySaved() {
        when(savedJobRepository.existsByJob_IdAndSeeker_Id(5L, 42L)).thenReturn(true);

        service.save(42L, 5L);

        verifyNoInteractions(jobSearchService, userRepository);
        verify(savedJobRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    // The load goes through JobSearchService.findForDetail (reuse, not a second visibility
    // rule) - this test pins that call down, including which role it is made as, so a
    // seeker can never bookmark a job whose own /jobs/{id} page would 404 for them.
    @Test
    void saveLoadsTheJobThroughJobSearchServiceVisibilityRuleAndInsertsANewRow() {
        when(savedJobRepository.existsByJob_IdAndSeeker_Id(5L, 42L)).thenReturn(false);
        Job job = new Job();
        job.setId(5L);
        when(jobSearchService.findForDetail(5L, 42L, Role.JOB_SEEKER)).thenReturn(job);
        User priya = new User();
        priya.setId(42L);
        when(userRepository.findById(42L)).thenReturn(java.util.Optional.of(priya));

        service.save(42L, 5L);

        ArgumentCaptor<SavedJob> captor = ArgumentCaptor.forClass(SavedJob.class);
        verify(savedJobRepository).save(captor.capture());
        SavedJob saved = captor.getValue();
        assertThat(saved.getJob()).isSameAs(job);
        assertThat(saved.getSeeker()).isSameAs(priya);
        assertThat(saved.getSavedAt()).isEqualTo(NOW);
    }

    // A pending-approval or rejected job 404s on the standalone detail page for anyone but
    // its own employer or an admin (JobSearchService.findForDetail); a hand-crafted save
    // POST for that same job id must 404 identically, not quietly create a bookmark to a
    // job the seeker could never actually open.
    @Test
    void saveLetsAResourceNotFoundExceptionFromTheVisibilityCheckPropagate() {
        when(savedJobRepository.existsByJob_IdAndSeeker_Id(99L, 42L)).thenReturn(false);
        when(jobSearchService.findForDetail(99L, 42L, Role.JOB_SEEKER))
                .thenThrow(new ResourceNotFoundException("Job 99 is not visible to this viewer"));

        assertThatThrownBy(() -> service.save(42L, 99L)).isInstanceOf(ResourceNotFoundException.class);
        verify(savedJobRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unsaveDeletesByTheSeekerAndJobPair() {
        service.unsave(42L, 5L);
        verify(savedJobRepository).deleteByJob_IdAndSeeker_Id(5L, 42L);
    }

    @Test
    void listUsesTheCurrentPageSizeSetting() {
        SystemSettings settings = new SystemSettings();
        settings.setPageSize(10);
        when(settingsService.get()).thenReturn(settings);
        Page<SavedJob> emptyPage = new PageImpl<>(java.util.List.of());
        when(savedJobRepository.findBySeeker_IdOrderBySavedAtDesc(eq(42L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(emptyPage);

        service.list(42L, 2);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(savedJobRepository).findBySeeker_IdOrderBySavedAtDesc(eq(42L), captor.capture());
        assertThat(captor.getValue()).isEqualTo(PageRequest.of(2, 10));
    }
}
