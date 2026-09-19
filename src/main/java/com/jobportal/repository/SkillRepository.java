package com.jobportal.repository;

import com.jobportal.domain.Skill;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// The shared skill vocabulary (Section 10.8). Every lookup is by slug, never by label:
// the slug is the identity, the label is only what gets rendered.
//
// Both finders are derived queries rather than @Query strings on purpose. The one JPQL
// LIKE/concat in this project had to be rewritten with cast(...) to start at all on
// PostgreSQL, so anything that can be expressed as a derived query or a Criteria
// predicate is (see JobSpecifications.likePattern for the same rule applied to patterns).
public interface SkillRepository extends JpaRepository<Skill, Long> {

    Optional<Skill> findBySlug(String slug);

    // One query for a whole typed-in list, instead of one per skill. Slugs not yet in the
    // table are simply absent from the result; SkillService creates those.
    List<Skill> findBySlugIn(Collection<String> slugs);
}
