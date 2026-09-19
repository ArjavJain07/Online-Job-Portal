package com.jobportal.domain;

import com.jobportal.domain.enums.JobType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.BatchSize;

// The extra profile fields a job seeker has and an employer does not. Created empty at
// registration; the resume fields are filled in later by SeekerProfileService.uploadResume.
@Entity
@Table(name = "seeker_profiles")
public class SeekerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    @Column(length = 120)
    private String headline;

    @Column(length = 15)
    private String phone;

    @Column(length = 100)
    private String location;

    // The seeker's skills as shared Skill rows (Section 10.8) - the same rows the jobs
    // point at, which is what lets RecommendationScorer compare skill identity instead of
    // spellings. Ordered, for the same reason Job.skills is: the order is what the seeker
    // typed, and it is the order their matched skills are listed back to them in the
    // "Matches your skills: ..." reason (Section 7.8).
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "seeker_profile_skills",
            joinColumns = @JoinColumn(name = "seeker_profile_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    @OrderColumn(name = "display_order")
    // Batched for the same reason as Job.skills, on a smaller scale: the employer
    // applications list builds a CandidateProfile per row.
    @BatchSize(size = 50)
    private List<Skill> skills = new ArrayList<>();

    // The pre-Section-10.8 comma-separated column, kept only so that a rollback to the
    // previous release finds a seeker's skills where it expects them. Written by
    // assignSkills, read by nothing - see Job.legacySkills for the full reasoning.
    // Nullable here (unlike on jobs) because it always was: skills are optional on a
    // profile and mandatory on a job.
    @Column(name = "skills", length = 400)
    private String legacySkills;

    @Column(nullable = false)
    private int experienceYears;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private JobType preferredJobType;

    @Column(length = 200)
    private String education;

    @Column(length = 1000)
    private String about;

    @Column(length = 60)
    private String resumeStoredName;

    @Column(length = 150)
    private String resumeOriginalName;

    @Column(length = 100)
    private String resumeContentType;

    private Long resumeSizeBytes;

    private LocalDateTime resumeUploadedAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List<Skill> getSkills() {
        return List.copyOf(skills);
    }

    // The seeker's skills as display labels, in the order they typed them: the chips on
    // the profile page, the candidate panel an employer sees (CandidateProfile), and the
    // value the profile form is pre-filled with.
    public List<String> skillList() {
        List<String> result = new ArrayList<>();
        for (Skill skill : skills) {
            result.add(skill.getLabel());
        }
        return result;
    }

    // The ONLY way a profile's skills change - see Job.assignSkills.
    public void assignSkills(List<Skill> newSkills) {
        skills.clear();
        skills.addAll(newSkills);
        List<String> labels = new ArrayList<>();
        for (Skill skill : newSkills) {
            labels.add(skill.getLabel());
        }
        legacySkills = labels.isEmpty() ? null : String.join(", ", labels);
    }

    // The rollback column's raw content; see Job.legacySkillsCsv.
    String legacySkillsCsv() {
        return legacySkills;
    }

    public int getExperienceYears() {
        return experienceYears;
    }

    public void setExperienceYears(int experienceYears) {
        this.experienceYears = experienceYears;
    }

    public JobType getPreferredJobType() {
        return preferredJobType;
    }

    public void setPreferredJobType(JobType preferredJobType) {
        this.preferredJobType = preferredJobType;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }

    public String getResumeStoredName() {
        return resumeStoredName;
    }

    public void setResumeStoredName(String resumeStoredName) {
        this.resumeStoredName = resumeStoredName;
    }

    public String getResumeOriginalName() {
        return resumeOriginalName;
    }

    public void setResumeOriginalName(String resumeOriginalName) {
        this.resumeOriginalName = resumeOriginalName;
    }

    public String getResumeContentType() {
        return resumeContentType;
    }

    public void setResumeContentType(String resumeContentType) {
        this.resumeContentType = resumeContentType;
    }

    public Long getResumeSizeBytes() {
        return resumeSizeBytes;
    }

    public void setResumeSizeBytes(Long resumeSizeBytes) {
        this.resumeSizeBytes = resumeSizeBytes;
    }

    public LocalDateTime getResumeUploadedAt() {
        return resumeUploadedAt;
    }

    public void setResumeUploadedAt(LocalDateTime resumeUploadedAt) {
        this.resumeUploadedAt = resumeUploadedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
