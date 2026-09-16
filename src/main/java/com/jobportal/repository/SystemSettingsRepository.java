package com.jobportal.repository;

import com.jobportal.domain.SystemSettings;
import org.springframework.data.jpa.repository.JpaRepository;

// A single row, id = 1 (Section 7.5). SettingsService.get() calls the inherited
// findById(1L) directly, so no extra methods are needed here.
public interface SystemSettingsRepository extends JpaRepository<SystemSettings, Long> {
}
