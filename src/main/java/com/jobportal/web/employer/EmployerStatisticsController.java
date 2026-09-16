package com.jobportal.web.employer;

import com.jobportal.domain.Job;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.security.AppUserDetails;
import com.jobportal.service.EmployerStatisticsService;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

// Employer application/candidate-engagement statistics (Section 6.3 E-D5, 7.6), always
// scoped to the signed-in employer's own jobs. Thin controller (11.3 contract item 1):
// every KPI, chart and table is built by EmployerStatisticsService (read-only, so it never
// calls ActivityLogService). "days" and "jobId" are forwarded exactly as they arrived,
// unparsed - both bound as plain String (Section 7.9 binding rule), so a non-numeric or
// foreign value never reaches a 404 handler; the service itself falls back to 30 days /
// "all own jobs" when either is invalid.
@Controller
public class EmployerStatisticsController {

    private final EmployerStatisticsService employerStatisticsService;
    private final JobRepository jobRepository;

    public EmployerStatisticsController(EmployerStatisticsService employerStatisticsService, JobRepository jobRepository) {
        this.employerStatisticsService = employerStatisticsService;
        this.jobRepository = jobRepository;
    }

    @GetMapping("/employer/statistics")
    public String statistics(@RequestParam(required = false) String days, @RequestParam(required = false) String jobId,
            @AuthenticationPrincipal AppUserDetails me, Model model) {
        model.addAttribute("stats", employerStatisticsService.getStatistics(me.getId(), days, jobId));
        model.addAttribute("ownJobs", ownJobs(me.getId()));
        return "employer/statistics";
    }

    // Every one of this employer's own jobs, any status, for the job-filter select
    // (the same JobSpecifications.hasEmployer query EmployerStatisticsService itself uses
    // for the all-jobs per-job table, so the dropdown never offers a job the table can't
    // show - Section 6.3 E-D5 "Range buttons and a job select").
    private List<Job> ownJobs(Long employerId) {
        return jobRepository.findAll(JobSpecifications.hasEmployer(employerId), Sort.by(Sort.Order.asc("title")));
    }
}
