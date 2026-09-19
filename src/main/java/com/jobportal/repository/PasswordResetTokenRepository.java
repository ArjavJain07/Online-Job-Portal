package com.jobportal.repository;

import com.jobportal.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    // The only lookup a presented token ever needs (PasswordResetService): the raw value
    // is hashed by the caller first, so this is always an exact, indexed match on
    // token_hash - never a scan, and the raw token itself never appears in a query.
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    // Invalidates every OTHER outstanding token for a user (PasswordResetService,
    // requestReset): called before a fresh one is inserted, so a stale email link stops
    // working the moment a newer one is sent, and only ever one row is "live" per user in
    // the common, sequential case. Also called by UserService.delete (Section 5.8) - this
    // table has no ON DELETE clause (V3 migration, matching V1's convention), so an admin
    // deleting a user who has an outstanding reset token would otherwise hit the same
    // foreign-key RESTRICT that protects jobs/applications/messages, and be told the
    // generic "related data exists" instead of the delete simply succeeding.
    long deleteByUser_Id(Long userId);

    // Belt-and-suspenders cleanup at the moment a token is CONSUMED (PasswordResetService,
    // resetPassword): deleteByUser_Id above already keeps "at most one live token" true in
    // the sequential case, but two overlapping "forgot password" submissions for the same
    // address could each insert before either's delete is visible to the other. This
    // removes any sibling that race left behind, so using the token that arrived does not
    // leave an older, still-technically-valid link sitting in some other inbox.
    long deleteByUser_IdAndIdNot(Long userId, Long id);
}
