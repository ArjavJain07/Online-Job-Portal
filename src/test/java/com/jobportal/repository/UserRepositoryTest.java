package com.jobportal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobportal.domain.User;
import com.jobportal.domain.enums.Role;
import com.jobportal.repository.projection.CurrentUserView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

// Repository-slice tests for UserRepository (Section 12.2): the DB-level unique email
// constraint, and the CurrentUserInterceptor projection.
@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void uniqueEmail() {
        userRepository.saveAndFlush(newUser("dup@example.com"));

        User second = newUser("dup@example.com");
        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void currentUserProjection() {
        User user = newUser("proj@example.com");
        user.setRole(Role.EMPLOYER);
        user.setCompanyName("Acme Technologies");
        User saved = entityManager.persistFlushFind(user);

        CurrentUserView view = userRepository.findProjectedById(saved.getId()).orElseThrow();

        assertThat(view.getId()).isEqualTo(saved.getId());
        assertThat(view.getFullName()).isEqualTo("Test User");
        assertThat(view.getEmail()).isEqualTo("proj@example.com");
        assertThat(view.getRole()).isEqualTo(Role.EMPLOYER);
        assertThat(view.isEnabled()).isTrue();
        assertThat(view.getCompanyName()).isEqualTo("Acme Technologies");
    }

    @Test
    void currentUserProjectionMissingIdIsEmpty() {
        assertThat(userRepository.findProjectedById(999_999L)).isEmpty();
    }

    private User newUser(String email) {
        User user = new User();
        user.setFullName("Test User");
        user.setEmail(email);
        user.setPasswordHash("hashed-password");
        user.setRole(Role.JOB_SEEKER);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
