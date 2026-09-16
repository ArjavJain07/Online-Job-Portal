package com.jobportal.seed;

import com.jobportal.config.AppProperties;
import com.jobportal.domain.ApplicationStatusChange;
import com.jobportal.domain.Job;
import com.jobportal.domain.JobApplication;
import com.jobportal.domain.JobStatusChange;
import com.jobportal.domain.Message;
import com.jobportal.domain.SeekerProfile;
import com.jobportal.domain.User;
import com.jobportal.domain.enums.ActivityType;
import com.jobportal.domain.enums.ApplicationStatus;
import com.jobportal.domain.enums.JobCategory;
import com.jobportal.domain.enums.JobStatus;
import com.jobportal.domain.enums.JobType;
import com.jobportal.domain.enums.Role;
import com.jobportal.domain.enums.TargetType;
import com.jobportal.domain.enums.WorkMode;
import com.jobportal.dto.StoredFile;
import com.jobportal.repository.ApplicationStatusChangeRepository;
import com.jobportal.repository.JobApplicationRepository;
import com.jobportal.repository.JobRepository;
import com.jobportal.repository.JobStatusChangeRepository;
import com.jobportal.repository.MessageRepository;
import com.jobportal.repository.SeekerProfileRepository;
import com.jobportal.repository.UserRepository;
import com.jobportal.service.ActivityLogService;
import com.jobportal.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Inserts the exact dataset of Section 13 (accounts, profiles, jobs, applications,
// messages, activity log) once, in one transaction, when DataSeeder decides demo data is
// needed. Every timestamp is written as "N days ago at a fixed hour" from the injected
// Clock, so the dataset always looks recent however long ago it was actually seeded.
// Activity log rows are collected as they are created and inserted last, sorted by time,
// so their ids increase with time (Section 13.1, 7.7).
@Component
public class DemoDataLoader {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private static final String EMPLOYER_PASSWORD = "Employer@123";
    private static final String SEEKER_PASSWORD = "Seeker@123";
    private static final String SAMPLE_RESUME_PATH = "demo/sample-resume.pdf";

    private static final String PRIYA_RESUME = "Priya_Sharma_Resume.pdf";
    private static final String ARJUN_RESUME = "Arjun_Mehta_Resume.pdf";
    private static final String ROHAN_RESUME = "Rohan_Das_Resume.pdf";
    private static final String SNEHA_RESUME = "Sneha_Iyer_Resume.pdf";

    // Fixed hours used across the whole dataset (Section 13.1: "days ago at a fixed
    // hour"). Keeping one hour per kind of event is enough to make ordering
    // deterministic; only the messages (Section 13.6) need their own exact times.
    private static final int CREATED_HOUR = 10;
    private static final int LOGIN_HOUR = 9;
    private static final int JOB_POSTED_HOUR = 10;
    private static final int JOB_DECISION_HOUR = 11;
    private static final int APPLY_HOUR = 10;
    private static final int STATUS_HOUR = 11;
    private static final int VIEWED_HOUR = 15;

    private final UserRepository userRepository;
    private final SeekerProfileRepository seekerProfileRepository;
    private final JobRepository jobRepository;
    private final JobStatusChangeRepository jobStatusChangeRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final ApplicationStatusChangeRepository applicationStatusChangeRepository;
    private final MessageRepository messageRepository;
    private final ActivityLogService activityLogService;
    private final FileStorageService fileStorageService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final Clock clock;

    public DemoDataLoader(UserRepository userRepository, SeekerProfileRepository seekerProfileRepository,
            JobRepository jobRepository, JobStatusChangeRepository jobStatusChangeRepository,
            JobApplicationRepository jobApplicationRepository,
            ApplicationStatusChangeRepository applicationStatusChangeRepository, MessageRepository messageRepository,
            ActivityLogService activityLogService, FileStorageService fileStorageService,
            PasswordEncoder passwordEncoder, AppProperties appProperties, Clock clock) {
        this.userRepository = userRepository;
        this.seekerProfileRepository = seekerProfileRepository;
        this.jobRepository = jobRepository;
        this.jobStatusChangeRepository = jobStatusChangeRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.applicationStatusChangeRepository = applicationStatusChangeRepository;
        this.messageRepository = messageRepository;
        this.activityLogService = activityLogService;
        this.fileStorageService = fileStorageService;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public void load() {
        LocalDate today = LocalDate.now(clock);
        List<PendingLog> pendingLogs = new ArrayList<>();

        User admin = backdateAdmin(today, pendingLogs);
        Map<String, User> employers = createEmployers(admin, today, pendingLogs);
        Map<String, User> seekers = createSeekers(today, pendingLogs);
        createSeekerProfiles(seekers);
        Map<String, Job> jobs = createJobs(today, admin, employers, pendingLogs);
        Map<String, JobApplication> applications = createApplications(today, jobs, seekers, employers, pendingLogs);
        createMessages(today, employers, seekers, applications, pendingLogs);

        // Insert the activity log last, oldest first, so log ids increase with time
        // exactly as they would if these events had happened one after another.
        pendingLogs.sort(Comparator.comparing(PendingLog::at));
        for (PendingLog entry : pendingLogs) {
            activityLogService.logAt(entry.type(), entry.actor(), entry.description(), entry.targetType(),
                    entry.targetId(), entry.at());
        }

        log.info("Demo dataset loaded (Section 13): {} users, {} jobs, {} applications, {} activity log rows.",
                1 + employers.size() + seekers.size(), jobs.size(), applications.size(), pendingLogs.size());
    }

    // ---- Accounts (Section 13.2) ----

    // The admin account already exists (DataSeeder creates it on the very first start).
    // Here it is back-dated to the demo's "created 60 days ago, last login 1 day ago" so
    // it fits the rest of the seeded timeline, and it gets the one LOGIN_SUCCESS row every
    // other user with a last login also gets.
    private User backdateAdmin(LocalDate today, List<PendingLog> pendingLogs) {
        String adminEmail = appProperties.seed().adminEmail().trim().toLowerCase(Locale.ROOT);
        User admin = userRepository.findByEmail(adminEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "The default admin must exist before demo data is loaded (DataSeeder ordering)."));
        admin.setCreatedAt(at(today, 60, CREATED_HOUR, 0));
        LocalDateTime lastLogin = at(today, 1, LOGIN_HOUR, 0);
        admin.setLastLoginAt(lastLogin);
        userRepository.save(admin);
        pendingLogs.add(loginLog(admin, lastLogin));
        return admin;
    }

    private Map<String, User> createEmployers(User admin, LocalDate today, List<PendingLog> pendingLogs) {
        Map<String, User> employers = new LinkedHashMap<>();

        User anita = newEmployer("Anita Rao", "hr@acme.local", "Acme Technologies", "https://acme.example",
                "Product engineering company building HR software in Pune.", true,
                at(today, 50, CREATED_HOUR, 0), at(today, 1, LOGIN_HOUR, 0));
        userRepository.save(anita);
        pendingLogs.add(registeredLog(anita));
        pendingLogs.add(loginLog(anita, anita.getLastLoginAt()));
        employers.put("acme", anita);

        User vikram = newEmployer("Vikram Nair", "talent@globex.local", "Globex Retail", null,
                "Retail chain with 40 stores across Maharashtra.", true,
                at(today, 50, CREATED_HOUR, 0), at(today, 2, LOGIN_HOUR, 0));
        userRepository.save(vikram);
        pendingLogs.add(registeredLog(vikram));
        pendingLogs.add(loginLog(vikram, vikram.getLastLoginAt()));
        employers.put("globex", vikram);

        User suresh = newEmployer("Suresh Pillai", "jobs@quickhire.local", "QuickHire Staffing", null, null, false,
                at(today, 48, CREATED_HOUR, 0), at(today, 8, LOGIN_HOUR, 0));
        userRepository.save(suresh);
        pendingLogs.add(registeredLog(suresh));
        pendingLogs.add(loginLog(suresh, suresh.getLastLoginAt()));
        // Deactivated by the admin a week ago (Section 13.2), after registering.
        pendingLogs.add(new PendingLog(ActivityType.USER_STATUS_CHANGED, admin,
                admin.getFullName() + " deactivated QuickHire Staffing (Suresh Pillai)", TargetType.USER,
                suresh.getId(), at(today, 7, CREATED_HOUR, 0)));
        employers.put("quickhire", suresh);

        return employers;
    }

    private Map<String, User> createSeekers(LocalDate today, List<PendingLog> pendingLogs) {
        Map<String, User> seekers = new LinkedHashMap<>();
        seekers.put("priya", newSeekerWithLog("Priya Sharma", "priya@demo.local",
                at(today, 35, CREATED_HOUR, 0), at(today, 1, LOGIN_HOUR, 0), pendingLogs));
        seekers.put("arjun", newSeekerWithLog("Arjun Mehta", "arjun@demo.local",
                at(today, 40, CREATED_HOUR, 0), at(today, 5, LOGIN_HOUR, 0), pendingLogs));
        seekers.put("rohan", newSeekerWithLog("Rohan Das", "rohan@demo.local",
                at(today, 40, CREATED_HOUR, 0), at(today, 3, LOGIN_HOUR, 0), pendingLogs));
        seekers.put("sneha", newSeekerWithLog("Sneha Iyer", "sneha@demo.local",
                at(today, 31, CREATED_HOUR, 0), at(today, 10, LOGIN_HOUR, 0), pendingLogs));
        // Neha and Karan have never logged in, so they get no LOGIN_SUCCESS row.
        seekers.put("neha", newSeekerWithLog("Neha Verma", "neha@demo.local",
                at(today, 2, CREATED_HOUR, 0), null, pendingLogs));
        seekers.put("karan", newSeekerWithLog("Karan Singh", "karan@demo.local",
                at(today, 3, CREATED_HOUR, 0), null, pendingLogs));
        return seekers;
    }

    private User newEmployer(String fullName, String email, String companyName, String companyWebsite,
            String companyDescription, boolean enabled, LocalDateTime createdAt, LocalDateTime lastLoginAt) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(EMPLOYER_PASSWORD));
        user.setRole(Role.EMPLOYER);
        user.setEnabled(enabled);
        user.setCompanyName(companyName);
        user.setCompanyWebsite(companyWebsite);
        user.setCompanyDescription(companyDescription);
        user.setCreatedAt(createdAt);
        user.setLastLoginAt(lastLoginAt);
        return user;
    }

    private User newSeekerWithLog(String fullName, String email, LocalDateTime createdAt, LocalDateTime lastLoginAt,
            List<PendingLog> pendingLogs) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(SEEKER_PASSWORD));
        user.setRole(Role.JOB_SEEKER);
        user.setEnabled(true);
        user.setCreatedAt(createdAt);
        user.setLastLoginAt(lastLoginAt);
        userRepository.save(user);

        pendingLogs.add(registeredLog(user));
        if (lastLoginAt != null) {
            pendingLogs.add(loginLog(user, lastLoginAt));
        }
        return user;
    }

    // ---- Seeker profiles (Section 13.3) ----

    private void createSeekerProfiles(Map<String, User> seekers) {
        User priya = seekers.get("priya");
        SeekerProfile priyaProfile = new SeekerProfile();
        priyaProfile.setUser(priya);
        priyaProfile.setHeadline("Java backend developer");
        priyaProfile.setLocation("Pune");
        priyaProfile.setSkills("Java, Spring Boot, SQL, Git");
        priyaProfile.setExperienceYears(2);
        priyaProfile.setPreferredJobType(JobType.FULL_TIME);
        priyaProfile.setPhone("9876500001");
        priyaProfile.setEducation("B.E. Computer Engineering, Pune, 2024");
        priyaProfile.setAbout("I build REST APIs with Spring Boot and enjoy clean, tested code.");
        attachResume(priyaProfile, PRIYA_RESUME, priya.getCreatedAt());
        seekerProfileRepository.save(priyaProfile);

        User arjun = seekers.get("arjun");
        SeekerProfile arjunProfile = new SeekerProfile();
        arjunProfile.setUser(arjun);
        arjunProfile.setLocation("Mumbai");
        arjunProfile.setSkills("Python, SQL, Excel");
        arjunProfile.setExperienceYears(1);
        attachResume(arjunProfile, ARJUN_RESUME, arjun.getCreatedAt());
        seekerProfileRepository.save(arjunProfile);

        User rohan = seekers.get("rohan");
        SeekerProfile rohanProfile = new SeekerProfile();
        rohanProfile.setUser(rohan);
        rohanProfile.setHeadline("Customer support specialist");
        rohanProfile.setLocation("Mumbai");
        rohanProfile.setSkills("Communication, CRM, Java");
        rohanProfile.setExperienceYears(3);
        rohanProfile.setPreferredJobType(JobType.FULL_TIME);
        rohanProfile.setPhone("9876500003");
        attachResume(rohanProfile, ROHAN_RESUME, rohan.getCreatedAt());
        seekerProfileRepository.save(rohanProfile);

        User sneha = seekers.get("sneha");
        SeekerProfile snehaProfile = new SeekerProfile();
        snehaProfile.setUser(sneha);
        snehaProfile.setHeadline("Frontend developer");
        snehaProfile.setLocation("Pune");
        snehaProfile.setSkills("HTML, CSS, JavaScript, React");
        snehaProfile.setExperienceYears(1);
        snehaProfile.setPreferredJobType(JobType.FULL_TIME);
        snehaProfile.setEducation("B.Sc. Information Technology, 2025");
        attachResume(snehaProfile, SNEHA_RESUME, sneha.getCreatedAt());
        seekerProfileRepository.save(snehaProfile);

        // Neha and Karan keep the empty profile every seeker gets at registration
        // (0% complete, Section 13.3): no headline, skills, resume or other extras.
        SeekerProfile nehaProfile = new SeekerProfile();
        nehaProfile.setUser(seekers.get("neha"));
        seekerProfileRepository.save(nehaProfile);

        SeekerProfile karanProfile = new SeekerProfile();
        karanProfile.setUser(seekers.get("karan"));
        seekerProfileRepository.save(karanProfile);
    }

    private void attachResume(SeekerProfile profile, String resumeFileName, LocalDateTime uploadedAt) {
        StoredFile resume = fileStorageService.storeSeedFile(sampleResumeStream(), resumeFileName);
        profile.setResumeStoredName(resume.storedName());
        profile.setResumeOriginalName(resume.originalName());
        profile.setResumeContentType(resume.contentType());
        profile.setResumeSizeBytes(resume.sizeBytes());
        profile.setResumeUploadedAt(uploadedAt);
        profile.setUpdatedAt(uploadedAt);
    }

    // ---- Jobs (Section 13.4) ----

    // Descriptions and requirements mention only each job's own listed skills (Section
    // 13.4), so recommendation and search results stay exactly as the ACs expect.
    private Map<String, Job> createJobs(LocalDate today, User admin, Map<String, User> employers,
            List<PendingLog> pendingLogs) {
        User acme = employers.get("acme");
        User globex = employers.get("globex");
        User quickhire = employers.get("quickhire");
        Map<String, Job> jobs = new LinkedHashMap<>();

        Job j1 = postJob(acme, "Java Developer",
                "We are looking for a Java Developer to join our backend team in Pune. You will build REST "
                        + "APIs using Java and Spring Boot, and write SQL queries against our production "
                        + "database. Familiarity with Git for version control is expected.",
                "Strong hands-on experience with Java and Spring Boot. Comfortable writing SQL queries and "
                        + "using Git in a team workflow.",
                "Java, Spring Boot, SQL, Git", JobCategory.SOFTWARE_DEVELOPMENT, JobType.FULL_TIME,
                WorkMode.HYBRID, "Pune", 600000, 900000, 1, 40, today.plusDays(25),
                at(today, 20, JOB_POSTED_HOUR, 0), pendingLogs);
        approveJob(j1, admin, at(today, 19, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J1", j1);

        Job j2 = postJob(acme, "Spring Boot Intern",
                "Join Acme Technologies as a Spring Boot Intern and learn to build real backend features. You "
                        + "will work alongside senior engineers on Java and Spring Boot services. This is a "
                        + "great first step into professional software development.",
                "Basic knowledge of Java and Spring Boot from college coursework or personal projects. "
                        + "Willingness to learn is more important than prior work experience.",
                "Java, Spring Boot", JobCategory.SOFTWARE_DEVELOPMENT, JobType.INTERNSHIP, WorkMode.ONSITE,
                "Pune", 180000, 240000, 0, 12, today.plusDays(20), at(today, 6, JOB_POSTED_HOUR, 0), pendingLogs);
        approveJob(j2, admin, at(today, 5, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J2", j2);

        Job j3 = postJob(acme, "Frontend Developer",
                "Acme Technologies is hiring a Frontend Developer to build user interfaces with HTML, CSS and "
                        + "JavaScript. You will work mainly with React to create responsive, accessible pages "
                        + "for our HR software. This is a fully remote role.",
                "Solid understanding of HTML, CSS and JavaScript. Experience building components with React.",
                "HTML, CSS, JavaScript, React", JobCategory.SOFTWARE_DEVELOPMENT, JobType.FULL_TIME,
                WorkMode.REMOTE, "Remote (India)", 500000, 800000, 2, 25, today.plusDays(15),
                at(today, 16, JOB_POSTED_HOUR, 0), pendingLogs);
        approveJob(j3, admin, at(today, 15, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J3", j3);

        Job j4 = postJob(acme, "QA Engineer",
                "We need a QA Engineer to test our Java backend services before every release. You will write "
                        + "automated tests with Selenium and maintain our testing checklist. Attention to "
                        + "detail matters more than years of experience.",
                "Experience with Selenium for automated testing. Working knowledge of Java. Comfortable "
                        + "planning and documenting testing efforts.",
                "Selenium, Java, Testing", JobCategory.SOFTWARE_DEVELOPMENT, JobType.FULL_TIME, WorkMode.ONSITE,
                "Bengaluru", 450000, 650000, 1, 8, today.plusDays(30), at(today, 11, JOB_POSTED_HOUR, 0),
                pendingLogs);
        approveJob(j4, admin, at(today, 10, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J4", j4);

        Job j5 = postJob(acme, "DevOps Engineer",
                "Acme Technologies is looking for a DevOps Engineer to manage our cloud infrastructure. You "
                        + "will containerise services with Docker, run workloads on AWS, and administer Linux "
                        + "servers. You will work closely with the backend team.",
                "Practical experience with Docker and AWS. Comfortable administering Linux servers and "
                        + "troubleshooting production issues.",
                "Docker, AWS, Linux", JobCategory.SOFTWARE_DEVELOPMENT, JobType.FULL_TIME, WorkMode.HYBRID,
                "Pune", 800000, 1200000, 3, 0, today.plusDays(30), at(today, 1, JOB_POSTED_HOUR, 0), pendingLogs);
        jobs.put("J5", j5); // still PENDING_APPROVAL, no decision yet

        Job j6 = postJob(globex, "Data Analyst",
                "Globex Retail is hiring a Data Analyst to study sales trends across our 40 stores. You will "
                        + "write SQL queries, build Excel reports, and use Python for deeper analysis. Power BI "
                        + "dashboards will be shared with store managers every week.",
                "Strong SQL skills and comfort with Excel for day to day reporting. Some Python experience and "
                        + "familiarity with Power BI.",
                "SQL, Excel, Python, Power BI", JobCategory.DATA_ANALYTICS, JobType.FULL_TIME, WorkMode.ONSITE,
                "Mumbai", 500000, 700000, 1, 30, today.plusDays(10), at(today, 26, JOB_POSTED_HOUR, 0),
                pendingLogs);
        approveJob(j6, admin, at(today, 25, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J6", j6);

        Job j7 = postJob(globex, "Marketing Executive",
                "Globex Retail needs a Marketing Executive to grow our online presence. You will plan SEO "
                        + "improvements, manage our Social Media accounts, and handle Content Writing for store "
                        + "promotions. This role reports to the marketing lead.",
                "Understanding of SEO basics and hands-on experience managing Social Media accounts. "
                        + "Comfortable with Content Writing for a retail audience.",
                "SEO, Social Media, Content Writing", JobCategory.MARKETING, JobType.FULL_TIME, WorkMode.ONSITE,
                "Mumbai", 350000, 500000, 0, 15, today.plusDays(18), at(today, 13, JOB_POSTED_HOUR, 0),
                pendingLogs);
        approveJob(j7, admin, at(today, 12, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J7", j7);

        Job j8 = postJob(globex, "Sales Intern",
                "Globex Retail is offering a Sales Intern position for someone who enjoys talking to "
                        + "customers. You will support the store team with day to day tasks and keep simple "
                        + "records in Excel. Clear Communication with customers is essential.",
                "Good Communication skills and comfort talking with customers. Basic Excel skills for keeping "
                        + "records.",
                "Communication, Excel", JobCategory.SALES, JobType.INTERNSHIP, WorkMode.ONSITE, "Mumbai", 120000,
                180000, 0, 0, today.plusDays(25), at(today, 2, JOB_POSTED_HOUR, 0), pendingLogs);
        jobs.put("J8", j8); // still PENDING_APPROVAL, no decision yet

        Job j9 = postJob(globex, "Store Manager",
                "Globex Retail is hiring a Store Manager to run daily operations at one of our stores. You "
                        + "will lead the store staff and handle Retail operations end to end. Strong Team "
                        + "Management skills are essential for this role.",
                "Prior Retail experience and proven Team Management skills. Comfortable handling day to day "
                        + "store operations independently.",
                "Retail, Team Management", JobCategory.OPERATIONS, JobType.FULL_TIME, WorkMode.ONSITE, "Mumbai",
                400000, 600000, 4, 0, today.plusDays(20), at(today, 9, JOB_POSTED_HOUR, 0), pendingLogs);
        rejectJob(j9, admin, "Description too vague: please add the store location and shift timings.",
                at(today, 8, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J9", j9);

        Job j10 = postJob(globex, "Customer Support Associate",
                "Globex Retail is hiring a Customer Support Associate to help customers by phone and email. "
                        + "You will use our CRM system to track every conversation. Clear Communication and "
                        + "patience are important for this role.",
                "Comfortable using a CRM system and handling customer queries. Good Communication skills, "
                        + "spoken and written.",
                "Communication, CRM", JobCategory.CUSTOMER_SUPPORT, JobType.FULL_TIME, WorkMode.ONSITE, "Mumbai",
                250000, 350000, 0, 50, today.minusDays(6), at(today, 45, JOB_POSTED_HOUR, 0), pendingLogs);
        approveJob(j10, admin, at(today, 44, JOB_DECISION_HOUR, 0), pendingLogs);
        closeJob(j10, at(today, 5, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J10", j10);

        Job j11 = postJob(acme, "Python Backend Developer",
                "Acme Technologies is looking for a Python Backend Developer to help build a new internal "
                        + "tool. You will write Python services with Django and design the underlying SQL "
                        + "schema. You will work closely with the existing Java team.",
                "Experience building backend services with Python and Django. Comfortable writing and "
                        + "optimising SQL queries.",
                "Python, Django, SQL", JobCategory.SOFTWARE_DEVELOPMENT, JobType.FULL_TIME, WorkMode.HYBRID,
                "Pune", 700000, 1000000, 2, 20, today.minusDays(2), at(today, 35, JOB_POSTED_HOUR, 0),
                pendingLogs);
        approveJob(j11, admin, at(today, 34, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J11", j11);

        Job j12 = postJob(quickhire, "Warehouse Supervisor",
                "QuickHire Staffing is hiring a Warehouse Supervisor for a client site in Pune. You will "
                        + "oversee Logistics for incoming and outgoing shipments and keep accurate Inventory "
                        + "records. Previous warehouse experience is preferred.",
                "Experience coordinating Logistics in a warehouse setting. Comfortable maintaining accurate "
                        + "Inventory records.",
                "Logistics, Inventory", JobCategory.OPERATIONS, JobType.FULL_TIME, WorkMode.ONSITE, "Pune",
                300000, 450000, 2, 0, today.plusDays(20), at(today, 15, JOB_POSTED_HOUR, 0), pendingLogs);
        approveJob(j12, admin, at(today, 14, JOB_DECISION_HOUR, 0), pendingLogs);
        jobs.put("J12", j12);

        return jobs;
    }

    // Every job starts here: saved as PENDING_APPROVAL with a "Posted" history row and a
    // JOB_POSTED log entry. approveJob/rejectJob/closeJob move it on from there.
    private Job postJob(User employer, String title, String description, String requirements, String skills,
            JobCategory category, JobType jobType, WorkMode workMode, String location, int salaryMin,
            int salaryMax, int minExperienceYears, int viewCount, LocalDate deadline, LocalDateTime postedAt,
            List<PendingLog> pendingLogs) {
        Job job = new Job();
        job.setEmployer(employer);
        job.setTitle(title);
        job.setDescription(description);
        job.setRequirements(requirements);
        job.setSkills(skills);
        job.setCategory(category);
        job.setJobType(jobType);
        job.setWorkMode(workMode);
        job.setLocation(location);
        job.setSalaryMin(salaryMin);
        job.setSalaryMax(salaryMax);
        job.setMinExperienceYears(minExperienceYears);
        job.setOpenings(1);
        job.setApplicationDeadline(deadline);
        job.setStatus(JobStatus.PENDING_APPROVAL);
        job.setViewCount(viewCount);
        job.setCreatedAt(postedAt);
        jobRepository.save(job);

        JobStatusChange posted = new JobStatusChange();
        posted.setJob(job);
        posted.setFromStatus(null);
        posted.setToStatus(JobStatus.PENDING_APPROVAL);
        posted.setActorName(composedName(employer));
        posted.setActorRole(Role.EMPLOYER);
        posted.setChangedAt(postedAt);
        jobStatusChangeRepository.save(posted);

        pendingLogs.add(new PendingLog(ActivityType.JOB_POSTED, employer,
                employer.getCompanyName() + " posted " + title, TargetType.JOB, job.getId(), postedAt));
        return job;
    }

    private void approveJob(Job job, User admin, LocalDateTime approvedAt, List<PendingLog> pendingLogs) {
        job.setStatus(JobStatus.APPROVED);
        job.setApprovedAt(approvedAt);
        job.setUpdatedAt(approvedAt);
        jobRepository.save(job);

        JobStatusChange change = new JobStatusChange();
        change.setJob(job);
        change.setFromStatus(JobStatus.PENDING_APPROVAL);
        change.setToStatus(JobStatus.APPROVED);
        change.setActorName(composedName(admin));
        change.setActorRole(Role.ADMIN);
        change.setChangedAt(approvedAt);
        jobStatusChangeRepository.save(change);

        pendingLogs.add(new PendingLog(ActivityType.JOB_APPROVED, admin,
                admin.getFullName() + " approved " + job.getTitle() + " (" + job.getEmployer().getCompanyName()
                        + ")",
                TargetType.JOB, job.getId(), approvedAt));
    }

    private void rejectJob(Job job, User admin, String reason, LocalDateTime rejectedAt,
            List<PendingLog> pendingLogs) {
        job.setStatus(JobStatus.REJECTED);
        job.setRejectionReason(reason);
        job.setUpdatedAt(rejectedAt);
        jobRepository.save(job);

        JobStatusChange change = new JobStatusChange();
        change.setJob(job);
        change.setFromStatus(JobStatus.PENDING_APPROVAL);
        change.setToStatus(JobStatus.REJECTED);
        change.setReason(reason);
        change.setActorName(composedName(admin));
        change.setActorRole(Role.ADMIN);
        change.setChangedAt(rejectedAt);
        jobStatusChangeRepository.save(change);

        pendingLogs.add(new PendingLog(ActivityType.JOB_REJECTED, admin,
                admin.getFullName() + " rejected " + job.getTitle() + " (" + job.getEmployer().getCompanyName()
                        + ")",
                TargetType.JOB, job.getId(), rejectedAt));
    }

    // Only J10 is closed in the seed data, and only ever from APPROVED (Section 13.4).
    private void closeJob(Job job, LocalDateTime closedAt, List<PendingLog> pendingLogs) {
        User employer = job.getEmployer();
        job.setStatus(JobStatus.CLOSED);
        job.setClosedAt(closedAt);
        job.setUpdatedAt(closedAt);
        jobRepository.save(job);

        JobStatusChange change = new JobStatusChange();
        change.setJob(job);
        change.setFromStatus(JobStatus.APPROVED);
        change.setToStatus(JobStatus.CLOSED);
        change.setActorName(composedName(employer));
        change.setActorRole(Role.EMPLOYER);
        change.setChangedAt(closedAt);
        jobStatusChangeRepository.save(change);

        pendingLogs.add(new PendingLog(ActivityType.JOB_CLOSED, employer,
                employer.getCompanyName() + " closed " + job.getTitle(), TargetType.JOB, job.getId(), closedAt));
    }

    // ---- Applications (Section 13.5) ----

    private Map<String, JobApplication> createApplications(LocalDate today, Map<String, Job> jobs,
            Map<String, User> seekers, Map<String, User> employers, List<PendingLog> pendingLogs) {
        User priya = seekers.get("priya");
        User arjun = seekers.get("arjun");
        User rohan = seekers.get("rohan");
        User sneha = seekers.get("sneha");
        User acme = employers.get("acme");
        User globex = employers.get("globex");
        Map<String, JobApplication> applications = new LinkedHashMap<>();

        // A1: Priya -> Java Developer. Applied, Under review, Shortlisted (note),
        // Interview (note); viewed after the last change, so no "Updated" badge.
        JobApplication a1 = submitApplication(jobs.get("J1"), priya, PRIYA_RESUME, at(today, 18, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a1, ApplicationStatus.UNDER_REVIEW, null, acme, at(today, 16, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a1, ApplicationStatus.SHORTLISTED, "Great Spring Boot project experience.", acme,
                at(today, 14, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a1, ApplicationStatus.INTERVIEW, "Technical interview scheduled. Details by message.",
                acme, at(today, 10, STATUS_HOUR, 0), pendingLogs);
        markViewed(a1, at(today, 9, VIEWED_HOUR, 0));
        applications.put("A1", a1);

        // A2: Rohan -> Java Developer. Applied, Under review, Shortlisted; never viewed,
        // so the "Updated" badge stays on.
        JobApplication a2 = submitApplication(jobs.get("J1"), rohan, ROHAN_RESUME, at(today, 15, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a2, ApplicationStatus.UNDER_REVIEW, null, acme, at(today, 13, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a2, ApplicationStatus.SHORTLISTED, null, acme, at(today, 3, STATUS_HOUR, 0), pendingLogs);
        applications.put("A2", a2);

        // A3: Arjun -> Java Developer. Applied, Under review, Rejected (note).
        JobApplication a3 = submitApplication(jobs.get("J1"), arjun, ARJUN_RESUME, at(today, 17, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a3, ApplicationStatus.UNDER_REVIEW, null, acme, at(today, 15, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a3, ApplicationStatus.REJECTED, "We are looking for more Java experience.", acme,
                at(today, 12, STATUS_HOUR, 0), pendingLogs);
        markViewed(a3, at(today, 11, VIEWED_HOUR, 0));
        applications.put("A3", a3);

        // A4: Sneha -> Java Developer. Applied only.
        applications.put("A4",
                submitApplication(jobs.get("J1"), sneha, SNEHA_RESUME, at(today, 1, APPLY_HOUR, 0), pendingLogs));

        // A5: Priya -> Frontend Developer. Applied, Under review; never viewed.
        JobApplication a5 = submitApplication(jobs.get("J3"), priya, PRIYA_RESUME, at(today, 9, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a5, ApplicationStatus.UNDER_REVIEW, null, acme, at(today, 2, STATUS_HOUR, 0), pendingLogs);
        applications.put("A5", a5);

        // A6: Sneha -> Frontend Developer. Applied ... Hired (note); viewed the same day,
        // after the change, so no "Updated" badge.
        JobApplication a6 = submitApplication(jobs.get("J3"), sneha, SNEHA_RESUME, at(today, 14, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a6, ApplicationStatus.UNDER_REVIEW, null, acme, at(today, 12, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a6, ApplicationStatus.SHORTLISTED, null, acme, at(today, 10, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a6, ApplicationStatus.INTERVIEW, null, acme, at(today, 7, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a6, ApplicationStatus.HIRED, "Welcome to the team!", acme, at(today, 4, STATUS_HOUR, 0),
                pendingLogs);
        markViewed(a6, at(today, 4, VIEWED_HOUR, 0));
        applications.put("A6", a6);

        // A7: Arjun -> Data Analyst. Applied, Under review, Shortlisted.
        JobApplication a7 = submitApplication(jobs.get("J6"), arjun, ARJUN_RESUME, at(today, 20, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a7, ApplicationStatus.UNDER_REVIEW, null, globex, at(today, 18, STATUS_HOUR, 0),
                pendingLogs);
        advanceStatus(a7, ApplicationStatus.SHORTLISTED, null, globex, at(today, 11, STATUS_HOUR, 0),
                pendingLogs);
        markViewed(a7, at(today, 10, VIEWED_HOUR, 0));
        applications.put("A7", a7);

        // A8: Rohan -> Data Analyst. Applied, Under review, then withdrawn by Rohan;
        // withdrawal sets seekerLastViewedAt to the same instant, so no badge appears.
        JobApplication a8 = submitApplication(jobs.get("J6"), rohan, ROHAN_RESUME, at(today, 22, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a8, ApplicationStatus.UNDER_REVIEW, null, globex, at(today, 19, STATUS_HOUR, 0),
                pendingLogs);
        withdraw(a8, rohan, at(today, 16, STATUS_HOUR, 0), pendingLogs);
        applications.put("A8", a8);

        // A9: Priya -> Data Analyst. Applied only.
        applications.put("A9",
                submitApplication(jobs.get("J6"), priya, PRIYA_RESUME, at(today, 4, APPLY_HOUR, 0), pendingLogs));

        // A10: Sneha -> Marketing Executive. Applied only.
        applications.put("A10", submitApplication(jobs.get("J7"), sneha, SNEHA_RESUME, at(today, 6, APPLY_HOUR, 0),
                pendingLogs));

        // A11: Arjun -> Marketing Executive. Applied, Under review.
        JobApplication a11 = submitApplication(jobs.get("J7"), arjun, ARJUN_RESUME, at(today, 8, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a11, ApplicationStatus.UNDER_REVIEW, null, globex, at(today, 7, STATUS_HOUR, 0),
                pendingLogs);
        markViewed(a11, at(today, 6, VIEWED_HOUR, 0));
        applications.put("A11", a11);

        // A12: Rohan -> Customer Support Associate. Applied ... Hired.
        JobApplication a12 = submitApplication(jobs.get("J10"), rohan, ROHAN_RESUME, at(today, 38, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a12, ApplicationStatus.UNDER_REVIEW, null, globex, at(today, 35, STATUS_HOUR, 0),
                pendingLogs);
        advanceStatus(a12, ApplicationStatus.SHORTLISTED, null, globex, at(today, 30, STATUS_HOUR, 0),
                pendingLogs);
        advanceStatus(a12, ApplicationStatus.INTERVIEW, null, globex, at(today, 20, STATUS_HOUR, 0), pendingLogs);
        advanceStatus(a12, ApplicationStatus.HIRED, null, globex, at(today, 8, STATUS_HOUR, 0), pendingLogs);
        markViewed(a12, at(today, 7, VIEWED_HOUR, 0));
        applications.put("A12", a12);

        // A13: Arjun -> Customer Support Associate. Applied, Under review, Rejected;
        // viewed the same day, after the change.
        JobApplication a13 = submitApplication(jobs.get("J10"), arjun, ARJUN_RESUME, at(today, 36, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a13, ApplicationStatus.UNDER_REVIEW, null, globex, at(today, 34, STATUS_HOUR, 0),
                pendingLogs);
        advanceStatus(a13, ApplicationStatus.REJECTED, null, globex, at(today, 9, STATUS_HOUR, 0), pendingLogs);
        markViewed(a13, at(today, 9, VIEWED_HOUR, 0));
        applications.put("A13", a13);

        // A14: Priya -> Python Backend Developer. Applied, Under review, Rejected.
        JobApplication a14 = submitApplication(jobs.get("J11"), priya, PRIYA_RESUME, at(today, 28, APPLY_HOUR, 0),
                pendingLogs);
        advanceStatus(a14, ApplicationStatus.UNDER_REVIEW, null, acme, at(today, 26, STATUS_HOUR, 0),
                pendingLogs);
        advanceStatus(a14, ApplicationStatus.REJECTED, null, acme, at(today, 21, STATUS_HOUR, 0), pendingLogs);
        markViewed(a14, at(today, 20, VIEWED_HOUR, 0));
        applications.put("A14", a14);

        // A15: Sneha -> Python Backend Developer. Applied, then withdrawn by Sneha
        // (straight from Applied, no Under review step).
        JobApplication a15 = submitApplication(jobs.get("J11"), sneha, SNEHA_RESUME, at(today, 25, APPLY_HOUR, 0),
                pendingLogs);
        withdraw(a15, sneha, at(today, 20, STATUS_HOUR, 0), pendingLogs);
        applications.put("A15", a15);

        return applications;
    }

    // Creates the application itself, its own resume copy and the first "Applied"
    // history row (Section 13.5: every application has one, actor the seeker).
    private JobApplication submitApplication(Job job, User seeker, String resumeFileName, LocalDateTime appliedAt,
            List<PendingLog> pendingLogs) {
        StoredFile resume = fileStorageService.storeSeedFile(sampleResumeStream(), resumeFileName);

        JobApplication application = new JobApplication();
        application.setJob(job);
        application.setSeeker(seeker);
        application.setStatus(ApplicationStatus.APPLIED);
        application.setResumeStoredName(resume.storedName());
        application.setResumeOriginalName(resume.originalName());
        application.setResumeContentType(resume.contentType());
        application.setResumeSizeBytes(resume.sizeBytes());
        application.setAppliedAt(appliedAt);
        jobApplicationRepository.save(application);

        ApplicationStatusChange applied = new ApplicationStatusChange();
        applied.setApplication(application);
        applied.setFromStatus(null);
        applied.setToStatus(ApplicationStatus.APPLIED);
        applied.setActorName(seeker.getFullName());
        applied.setActorRole(Role.JOB_SEEKER);
        applied.setChangedAt(appliedAt);
        applicationStatusChangeRepository.save(applied);

        pendingLogs.add(new PendingLog(ActivityType.APPLICATION_SUBMITTED, seeker,
                seeker.getFullName() + " applied for " + job.getTitle() + " at "
                        + job.getEmployer().getCompanyName() + " (" + application.getReference() + ")",
                TargetType.APPLICATION, application.getId(), appliedAt));
        return application;
    }

    // An employer-driven status change (5.6): writes the history row and statusChangedAt,
    // exactly what JobApplicationService.recordStatusChange does at request time, but with
    // an explicit past instant instead of the Clock's current one.
    private void advanceStatus(JobApplication application, ApplicationStatus newStatus, String noteToCandidate,
            User employer, LocalDateTime changedAt, List<PendingLog> pendingLogs) {
        ApplicationStatus previous = application.getStatus();
        application.setStatus(newStatus);
        application.setStatusChangedAt(changedAt);
        application.setUpdatedAt(changedAt);
        jobApplicationRepository.save(application);

        ApplicationStatusChange change = new ApplicationStatusChange();
        change.setApplication(application);
        change.setFromStatus(previous);
        change.setToStatus(newStatus);
        change.setNoteToCandidate(noteToCandidate);
        change.setActorName(composedName(employer));
        change.setActorRole(Role.EMPLOYER);
        change.setChangedAt(changedAt);
        applicationStatusChangeRepository.save(change);

        pendingLogs.add(new PendingLog(ActivityType.APPLICATION_STATUS_CHANGED, employer,
                employer.getCompanyName() + " moved " + application.getReference() + " to " + newStatus.getLabel(),
                TargetType.APPLICATION, application.getId(), changedAt));
    }

    // A seeker withdrawal: seekerLastViewedAt is set to the same instant as
    // statusChangedAt (Section 13.5), so the seeker's own action never raises a badge.
    private void withdraw(JobApplication application, User seeker, LocalDateTime changedAt,
            List<PendingLog> pendingLogs) {
        ApplicationStatus previous = application.getStatus();
        application.setStatus(ApplicationStatus.WITHDRAWN);
        application.setStatusChangedAt(changedAt);
        application.setSeekerLastViewedAt(changedAt);
        application.setUpdatedAt(changedAt);
        jobApplicationRepository.save(application);

        ApplicationStatusChange change = new ApplicationStatusChange();
        change.setApplication(application);
        change.setFromStatus(previous);
        change.setToStatus(ApplicationStatus.WITHDRAWN);
        change.setActorName(seeker.getFullName());
        change.setActorRole(Role.JOB_SEEKER);
        change.setChangedAt(changedAt);
        applicationStatusChangeRepository.save(change);

        pendingLogs.add(new PendingLog(ActivityType.APPLICATION_WITHDRAWN, seeker,
                seeker.getFullName() + " withdrew " + application.getReference(), TargetType.APPLICATION,
                application.getId(), changedAt));
    }

    private void markViewed(JobApplication application, LocalDateTime viewedAt) {
        application.setSeekerLastViewedAt(viewedAt);
        jobApplicationRepository.save(application);
    }

    // ---- Messages (Section 13.6) ----

    private void createMessages(LocalDate today, Map<String, User> employers, Map<String, User> seekers,
            Map<String, JobApplication> applications, List<PendingLog> pendingLogs) {
        User acme = employers.get("acme");
        User globex = employers.get("globex");
        User priya = seekers.get("priya");
        User rohan = seekers.get("rohan");
        User arjun = seekers.get("arjun");
        User sneha = seekers.get("sneha");

        JobApplication a1 = applications.get("A1");
        JobApplication a2 = applications.get("A2");
        JobApplication a6 = applications.get("A6");
        JobApplication a7 = applications.get("A7");

        sendMessage(a1, acme, priya,
                "Hi Priya, congratulations on reaching the interview stage. Are you free on Thursday at 11am?",
                at(today, 10, 11, 0), at(today, 10, 12, 0), pendingLogs);
        sendMessage(a1, priya, acme, "Yes, Thursday 11am works for me. Thank you!", at(today, 9, 9, 30),
                at(today, 9, 10, 30), pendingLogs);
        sendMessage(a1, acme, priya,
                "Please bring a printed copy of your resume. The interview is at our Pune office, 2nd floor.",
                at(today, 1, 16, 0), null, pendingLogs);
        sendMessage(a2, acme, rohan,
                "You've been shortlisted for Java Developer. We'll share interview slots this week.",
                at(today, 3, 12, 0), null, pendingLogs);
        sendMessage(a7, globex, arjun, "Thanks for applying. Could you share an example of a dashboard you have "
                + "built?", at(today, 11, 10, 0), at(today, 11, 11, 0), pendingLogs);
        sendMessage(a7, arjun, globex,
                "Sure. I built a sales dashboard in Power BI for my college fest and can show it in the "
                        + "interview.",
                at(today, 10, 18, 0), at(today, 10, 19, 0), pendingLogs);
        sendMessage(a6, acme, sneha, "Welcome aboard, Sneha! HR will email your offer letter today.",
                at(today, 4, 15, 0), at(today, 4, 16, 0), pendingLogs);
        sendMessage(a6, sneha, acme, "Thank you, I'm excited to join!", at(today, 4, 17, 0), null, pendingLogs);
    }

    private void sendMessage(JobApplication application, User sender, User recipient, String body,
            LocalDateTime sentAt, LocalDateTime readAt, List<PendingLog> pendingLogs) {
        Message message = new Message();
        message.setApplication(application);
        message.setSender(sender);
        message.setRecipient(recipient);
        message.setBody(body);
        message.setSentAt(sentAt);
        message.setReadAt(readAt);
        messageRepository.save(message);

        // MESSAGE_SENT never includes the body (Section 5.7): an employer "messages a
        // candidate", a seeker "replies" to the employer that started the thread.
        String description = sender.getRole() == Role.EMPLOYER
                ? sender.getCompanyName() + " messaged a candidate (" + application.getReference() + ")"
                : sender.getFullName() + " replied to " + recipient.getCompanyName() + " ("
                        + application.getReference() + ")";
        pendingLogs.add(new PendingLog(ActivityType.MESSAGE_SENT, sender, description, TargetType.APPLICATION,
                application.getId(), sentAt));
    }

    // ---- Small shared helpers ----

    private PendingLog registeredLog(User user) {
        String roleText = user.getRole() == Role.EMPLOYER ? "registered as an employer" : "registered as a job seeker";
        return new PendingLog(ActivityType.USER_REGISTERED, user, user.getFullName() + " " + roleText,
                TargetType.USER, user.getId(), user.getCreatedAt());
    }

    private PendingLog loginLog(User user, LocalDateTime loginAt) {
        return new PendingLog(ActivityType.LOGIN_SUCCESS, user, user.getFullName() + " logged in", TargetType.USER,
                user.getId(), loginAt);
    }

    // "fullName (companyName)" for employers, the plain name otherwise, "System" when no
    // one acted (Section 5.2) - mirrors JobService/JobApplicationService's own copy of
    // this rule, since DemoDataLoader writes history rows directly rather than through
    // those services (it needs past timestamps, not the Clock's current instant).
    private String composedName(User actor) {
        if (actor == null) {
            return "System";
        }
        if (actor.getRole() == Role.EMPLOYER && actor.getCompanyName() != null) {
            return actor.getFullName() + " (" + actor.getCompanyName() + ")";
        }
        return actor.getFullName();
    }

    private static LocalDateTime at(LocalDate today, int daysAgo, int hour, int minute) {
        return today.minusDays(daysAgo).atTime(hour, minute);
    }

    private InputStream sampleResumeStream() {
        try {
            return new ClassPathResource(SAMPLE_RESUME_PATH).getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the bundled demo resume.", e);
        }
    }

    // One planned activity log row, held in memory until load() sorts everything by time
    // and inserts it last (Section 13.1).
    private record PendingLog(ActivityType type, User actor, String description, TargetType targetType,
            Long targetId, LocalDateTime at) {
    }
}
