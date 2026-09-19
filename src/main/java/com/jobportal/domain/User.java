package com.jobportal.domain;

import com.jobportal.domain.enums.Role;
import com.jobportal.util.SearchIndex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

// One account, whatever the role. Employers keep their company details here instead of
// a separate profile entity (decision D-6); only job seekers get a SeekerProfile.
// There is no @OneToMany or inverse @OneToOne on this class on purpose (3.2/5.2):
// related rows (jobs, applications, messages) are found through repositories.
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 120)
    private String companyName;

    // Search index for companyName (Section 10.9, V7): SearchIndex.of(companyName), so
    // " acme technologies " - the form JobSpecifications.keyword matches a typed phrase
    // against with a single whole-word LIKE.
    //
    // It lives here, on the row that owns the name, rather than being copied onto each of
    // the employer's jobs so that the whole search could read one table. Copying it would
    // have been faster and wrong: an employer who renames the company would leave a stale
    // name on every job they have posted, and the search reads this on every request, so
    // V4's "a column nothing reads cannot drift into a wrong answer" would not save it.
    // Here, setCompanyName below is the only thing that can write it.
    //
    // NULLABLE, unlike the two on Job, because companyName is: only employers have one.
    // NULL therefore means "no company name OR not indexed yet", and the two are told
    // apart exactly by UserRepository.findWithUnindexedCompanyName's predicate
    // (companyName is not null and searchCompanyName is null).
    @Column(length = 122)
    private String searchCompanyName;

    @Column(length = 200)
    private String companyWebsite;

    @Column(length = 1000)
    private String companyDescription;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastLoginAt;

    // Brute-force lockout state (Section 4.10). Consecutive failed logins for this
    // account; reset to 0 by a successful login and by the first failure after a lockout
    // has expired. lockoutUntil is the instant the cooldown ends, or null when the
    // account is not locked.
    //
    // Both are Integer/LocalDateTime (nullable) rather than a primitive with
    // nullable = false: the app runs on ddl-auto=update over databases that already hold
    // rows (the hosted Postgres of Section 10), and Hibernate's ALTER TABLE ... ADD
    // COLUMN ... NOT NULL would fail there - silently, because hbm2ddl update only logs
    // schema errors. A nullable column is added successfully on every dialect, and null
    // simply means "has never failed", which failedLoginAttempts() below folds to 0.
    @Column(name = "failed_login_attempts")
    private Integer failedLoginAttempts;

    @Column(name = "lockout_until")
    private LocalDateTime lockoutUntil;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCompanyName() {
        return companyName;
    }

    // Setting the company name re-indexes it in the same call (Section 10.9), for the same
    // reason Job.setTitle does: a field written only inside @PreUpdate can be left out of
    // the UPDATE Hibernate generates, and a search index that stops tracking renames is
    // wrong in a way nothing would notice. All six callers of this setter - registration,
    // the employer's own profile edit, the admin user form (both create and edit), the
    // account service and DemoDataLoader - therefore maintain the index without knowing it
    // exists. The seventh, UserService's "demote an employer" path, passes null and
    // clears it, which is correct: a user who is no longer an employer has no company name
    // to find them by.
    public void setCompanyName(String companyName) {
        this.companyName = companyName;
        this.searchCompanyName = companyName == null ? null : SearchIndex.of(companyName);
    }

    // True when this user has a company name that predates Section 10.9 and has never been
    // indexed. Distinguishes "not indexed" from the far more common "not an employer, so
    // there is nothing to index", which are the same NULL in the column.
    public boolean needsSearchIndex() {
        return companyName != null && searchCompanyName == null;
    }

    // Rebuilds the index from the name already on this user, by re-running the one setter
    // that defines it (Section 10.9 backfill).
    public void reindexSearchText() {
        setCompanyName(companyName);
    }

    // The raw stored index. Package-private: only the test that proves the column is
    // really maintained reads it directly.
    String searchCompanyNameIndex() {
        return searchCompanyName;
    }

    public String getCompanyWebsite() {
        return companyWebsite;
    }

    public void setCompanyWebsite(String companyWebsite) {
        this.companyWebsite = companyWebsite;
    }

    public String getCompanyDescription() {
        return companyDescription;
    }

    public void setCompanyDescription(String companyDescription) {
        this.companyDescription = companyDescription;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    // Folds the nullable column to a count, so callers never have to think about the
    // "column added to an existing table" null (see the field comment above).
    public int getFailedLoginAttempts() {
        return failedLoginAttempts == null ? 0 : failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public LocalDateTime getLockoutUntil() {
        return lockoutUntil;
    }

    public void setLockoutUntil(LocalDateTime lockoutUntil) {
        this.lockoutUntil = lockoutUntil;
    }
}
