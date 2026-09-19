package com.jobportal.web.common;

import com.jobportal.domain.Job;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.exception.ResourceNotFoundException;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobSpecifications;
import com.jobportal.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// Public company pages (A-D6: company directory and company detail): routes anyone can
// open to browse employers and see their published jobs (Section 6.1 P-3, P-4). Uses the
// same "live job" visibility rule as JobBrowseController and the landing page, applied by
// JobSpecifications.live(today). An employer is listed only when they are enabled AND have
// at least one visible job; accessing a disabled employer or one with no visible jobs
// returns 404 the same way an invisible job does (Section 3.5 rule 3).
@Controller
public class CompanyBrowseController {

    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final Clock clock;

    public CompanyBrowseController(UserRepository userRepository, JobRepository jobRepository, Clock clock) {
        this.userRepository = userRepository;
        this.jobRepository = jobRepository;
        this.clock = clock;
    }

    // GET /companies: the public index of employers with at least one visible job
    // (A-D6). Shows a paginated list of employers; each employer card displays their
    // company name, description, and the count of visible jobs they have.
    @GetMapping("/companies")
    public String index(Model model) {
        LocalDate today = LocalDate.now(clock);

        // Specification to find all live jobs - we'll use this to identify employers
        // with visible jobs. The list() pagination will be handled by collecting
        // distinct employers from live jobs.
        Specification<Job> liveJobSpec = JobSpecifications.live(today);

        // Get employers with live jobs, sorted by most recent job posting
        List<Job> liveJobs = jobRepository.findAll(liveJobSpec,
                PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Order.desc("approvedAt")))).getContent();

        // Extract unique employers while preserving order (first appearance = most recent job)
        List<User> employers = liveJobs.stream()
                .map(Job::getEmployer)
                .distinct()
                .toList();

        model.addAttribute("employers", employers);
        return "public/companies";
    }

    // GET /companies/{id}: the public company detail page (A-D6). Shows the employer's
    // profile (name, description, website) and all of their currently visible jobs.
    // Throws 404 if the employer does not exist, is disabled, or has no visible jobs.
    @GetMapping("/companies/{id}")
    public String detail(@PathVariable Long id, Model model) {
        User employer = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company " + id + " does not exist"));

        // An employer is visible to the public only if they are enabled
        if (!employer.isEnabled()) {
            throw new ResourceNotFoundException("Company " + id + " is not visible to this viewer");
        }

        LocalDate today = LocalDate.now(clock);

        // Find all live jobs for this employer
        Specification<Job> spec = JobSpecifications.live(today)
                .and(JobSpecifications.hasEmployer(id));
        List<Job> jobs = jobRepository.findAll(spec,
                PageRequest.of(0, Integer.MAX_VALUE, Sort.by(Sort.Order.desc("approvedAt")))).getContent();

        // Employer must have at least one visible job to be publicly accessible
        if (jobs.isEmpty()) {
            throw new ResourceNotFoundException("Company " + id + " has no visible jobs");
        }

        model.addAttribute("company", employer);
        model.addAttribute("jobs", jobs);
        return "public/company-detail";
    }
}
