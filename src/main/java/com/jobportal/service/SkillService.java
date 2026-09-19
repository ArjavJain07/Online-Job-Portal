package com.jobportal.service;

import com.jobportal.domain.Skill;
import com.jobportal.repository.SkillRepository;
import com.jobportal.util.SkillParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Turns the free text an employer or a seeker types into shared Skill rows (Section
// 10.8). The single bridge between "what a person wrote in a text box" and "which rows
// of the skills table that is", used by JobService, SeekerProfileService and DataSeeder.
//
// FREE TEXT STAYS FREE TEXT (Section 6.3 E-F1, 6.4 S-F4)
// resolve() creates a row for any canonical key it has not seen before. Normalising
// skills deliberately did NOT turn them into a fixed, admin-curated vocabulary: an
// employer hiring for something nobody has posted before can still type it and have it
// work on the first try, exactly as before. What changed is that the second employer to
// type the same thing - in any spelling or punctuation - now lands on the same row, which
// is what makes identity matching and facet counts possible at all.
@Service
public class SkillService {

    private final SkillRepository skillRepository;

    public SkillService(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    // The typed CSV as Skill rows, in the order typed, normalised by SkillParser (trimmed,
    // inner spaces collapsed, blanks and punctuation-only entries dropped, duplicates
    // removed by canonical key, at most 30). Rows that do not exist yet are created with
    // the spelling the person just typed as their label.
    //
    // ON THE NARROW RACE HERE: two people posting a brand-new skill in the same instant
    // can both miss it in findBySlugIn and both try to insert it. The unique constraint on
    // skills.slug is the backstop - one of the two saves fails rather than creating a
    // duplicate row, and the employer retries successfully because the row now exists.
    // The tidier fix (resolving in its own REQUIRES_NEW transaction) was deliberately not
    // used: it would commit skill rows outside the caller's transaction, which in this
    // project's @Transactional, rolled-back integration tests means rows leaking from one
    // test into the next - the same class of cross-test contamination that makes
    // JobApplicationTest's hard-coded APP-00016 so fragile (Section 12.1).
    @Transactional
    public List<Skill> resolve(String csv) {
        List<String> labels = SkillParser.labels(csv);
        if (labels.isEmpty()) {
            return List.of();
        }

        Map<String, String> labelBySlug = new LinkedHashMap<>();
        for (String label : labels) {
            labelBySlug.put(SkillParser.canonical(label), label);
        }

        Map<String, Skill> existing = new LinkedHashMap<>();
        for (Skill skill : skillRepository.findBySlugIn(labelBySlug.keySet())) {
            existing.put(skill.getSlug(), skill);
        }

        List<Skill> resolved = new ArrayList<>();
        for (Map.Entry<String, String> entry : labelBySlug.entrySet()) {
            Skill skill = existing.get(entry.getKey());
            if (skill == null) {
                skill = skillRepository.save(Skill.of(entry.getValue()));
            }
            resolved.add(skill);
        }
        return resolved;
    }

    // One skill by its canonical key, for the search facet: the "skill" query parameter
    // carries a slug, and an unknown one means "no such facet" rather than an error
    // (Section 7.9's lenient query-parameter rule).
    @Transactional(readOnly = true)
    public Optional<Skill> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String canonical = SkillParser.canonical(slug);
        return canonical.isEmpty() ? Optional.empty() : skillRepository.findBySlug(canonical);
    }
}
