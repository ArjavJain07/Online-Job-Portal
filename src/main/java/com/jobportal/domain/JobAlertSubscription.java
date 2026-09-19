package com.jobportal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

// A job seeker's opt-in to the weekly new-jobs-that-match-you email (Section 16 future-work
// item 5: "job alerts", now built). One row per seeker who has EVER opted in - created the
// first time they turn alerts on and then kept forever, toggling `enabled` rather than being
// deleted and recreated, so `unsubscribeToken` (below) stays valid across an opt-out/opt-in
// cycle instead of silently breaking a link the seeker already has in an old email.
//
// OWN TABLE, NOT A FIELD ON SeekerProfile
// SeekerProfile is one of the entities frozen at foundation-v1 (11.3 contract item 1: "No
// new fields ... without agreement"); this feature is exactly the kind of later, additive
// slice that the project has already twice preferred to build as its own table instead
// (JobView for view analytics, PasswordResetToken for reset links) rather than widen a
// foundation entity. It also keeps this table's lifecycle - created on demand, read by an
// anonymous unsubscribe link, batch-scanned by a scheduler - independent of SeekerProfile's
// own (profile fields are only ever read/written by the logged-in owner).
//
// WHY unsubscribeToken IS STORED RAW, UNLIKE PasswordResetToken.tokenHash
// PasswordResetToken stores only a SHA-256 hash because a raw reset token is a
// password-equivalent bearer secret: whoever holds it can take over the account outright,
// so it gets the same "never at rest" treatment as users.password_hash (see that entity's
// own comment). This token's blast radius is entirely different: the ONE thing holding it
// lets someone do is flip this row's `enabled` to false, i.e. turn off a convenience email -
// no authentication, funds, personal data or account state is touched. It also cannot be
// single-use and hashed the way a reset token is, because - unlike a reset link, which is
// used once within minutes of being issued - the SAME link has to keep working correctly
// every time it appears at the bottom of a NEW digest email, for as long as the seeker stays
// subscribed (weeks or months apart). A hash-and-compare lookup only works when the caller
// can re-hash the value it already has; nothing on the receiving end of an unsubscribe click
// could ever recompute PasswordResetService-style hash to look itself up, so the value has to
// be stored the way it will be looked up: directly. It is still a 256-bit SecureRandom value
// (JobAlertService.newToken(), the exact generation recipe PasswordResetService.newRawToken()
// uses), so it is not guessable even sitting in the clear - the design choice here is "do not
// hash it", not "make it weak".
@Entity
@Table(name = "job_alert_subscriptions")
public class JobAlertSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "unsubscribe_token", nullable = false, unique = true, length = 43)
    private String unsubscribeToken;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Null means "never sent a digest yet" (JobAlertService: a fresh subscriber's first
    // digest covers every CURRENT match rather than only ones posted after they clicked
    // opt-in - see that class for the reasoning). Set only when a digest actually goes out,
    // never merely "checked and found nothing new" - see JobAlertService.sendDueDigests for
    // why leaving it untouched on an empty run is deliberate, not an oversight.
    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUnsubscribeToken() {
        return unsubscribeToken;
    }

    public void setUnsubscribeToken(String unsubscribeToken) {
        this.unsubscribeToken = unsubscribeToken;
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

    public LocalDateTime getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(LocalDateTime lastSentAt) {
        this.lastSentAt = lastSentAt;
    }
}
