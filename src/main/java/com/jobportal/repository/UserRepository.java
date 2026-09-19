package com.jobportal.repository;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.projection.CurrentUserView;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    // AppUserDetailsService loads by email (the login username); the caller trims and
    // lower-cases first, and every stored email is already lower-case (5.2), so an exact
    // match is enough. Also used for the duplicate-email check on registration and on
    // the admin user form.
    Optional<User> findByEmail(String email);

    // CurrentUserInterceptor reloads just these columns on every request (Section 4.6),
    // as a closed projection so the full entity and its lazy fields are never touched.
    Optional<CurrentUserView> findProjectedById(Long id);

    // Admin user management (A-D1, Section 7.9): q (name, email or company contains),
    // role and status are all optional. Callers pass q already trimmed and lower-cased,
    // or null for "no filter" (the same convention as role and enabled).
    // The casts matter on PostgreSQL: with a null :q the driver sends an untyped
    // parameter, so the server resolves || as bytea||bytea and LIKE then fails with
    // "operator does not exist: text ~~ bytea". H2 infers the type without them.
    @Query("select u from User u where "
            + "(:role is null or u.role = :role) "
            + "and (:enabled is null or u.enabled = :enabled) "
            + "and (:q is null "
            + "     or lower(u.fullName) like concat('%', cast(:q as String), '%') "
            + "     or lower(u.email) like concat('%', cast(:q as String), '%') "
            + "     or lower(u.companyName) like concat('%', cast(:q as String), '%'))")
    Page<User> search(@Param("q") String q, @Param("role") Role role, @Param("enabled") Boolean enabled, Pageable pageable);

    @Query("select u.createdAt from User u where u.createdAt >= :from")
    List<LocalDateTime> findCreatedAtSince(@Param("from") LocalDateTime from);

    // Active users (7 days), admin engagement metric.
    long countByLastLoginAtGreaterThanEqual(LocalDateTime from);

    // Denominators for the admin engagement ratios (Section 7.6): "enabled seekers" for
    // seeker participation, and "all employers" (enabled or not - a deactivated employer
    // still counts as an employer) for the employer posting rate. Not named as a
    // repository query in Section 7.6's table, but needed to compute the ratios it does
    // define; added here (AdminStatisticsService) rather than reusing the paginated
    // search() method just to read its totalElements.
    long countByRole(Role role);

    long countByRoleAndEnabled(Role role, boolean enabled);
}
