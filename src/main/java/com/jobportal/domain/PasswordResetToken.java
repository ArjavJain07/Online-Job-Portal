package com.jobportal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

// A single-use, emailed password-reset link (Section 16 #1 / I-17), one row per email
// sent. @ManyToOne rather than @OneToOne (contrast SeekerProfile.user): rows are not
// "the" token for a user the way a profile is "the" profile, they are history that
// accumulates and gets pruned by PasswordResetService, closer in shape to JobStatusChange
// than to SeekerProfile.
//
// tokenHash is the ONLY thing this row ever stores about the token itself - never the raw
// value. Hard requirement 4 treats a raw reset token as a password-equivalent secret
// (anyone holding it can take over the account without the real password), so it gets the
// same "never at rest in the database" treatment users.password_hash already gets, just
// with SHA-256 instead of BCrypt - see the migration's comment for why a fast, unsalted
// hash is the *correct* choice here rather than a shortcut.
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // Null means unused (5.2-style nullable-column-as-flag, matching
    // JobApplication.seekerLastViewedAt and Message.readAt). Set once, by
    // PasswordResetService.resetPassword, the moment the token is spent - never cleared,
    // never reused, which is what makes isUsable below a one-way door.
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    // The one rule this whole feature's single-use and expiry requirements (hard
    // requirement 4) boil down to, kept here for the same reason Job.isLive lives on Job
    // (Section 3.5 design rule 4, "one definition"): every caller - the GET preview, the
    // POST that consumes it - asks this one method instead of re-deriving the two
    // conditions, so they can never quietly drift apart from each other.
    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

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

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }
}
