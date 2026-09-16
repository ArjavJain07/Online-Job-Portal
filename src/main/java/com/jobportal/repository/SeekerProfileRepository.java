package com.jobportal.repository;

import com.jobportal.domain.SeekerProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeekerProfileRepository extends JpaRepository<SeekerProfile, Long> {

    // A seeker's own profile is always loaded by their own user id, never by a URL id
    // (Section 4.5).
    Optional<SeekerProfile> findByUser_Id(Long userId);
}
