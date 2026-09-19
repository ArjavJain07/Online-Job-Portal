package com.jobportal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobportal.domain.Skill;
import com.jobportal.repository.SkillRepository;
import com.jobportal.support.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Normalising skills (Section 10.8) must NOT have turned the two free-text boxes - the
// job form's Skills field (6.3 E-F1) and the seeker profile's (6.4 S-F4) - into a fixed
// list a user can only pick from. This is the test that says so: whatever somebody types
// still works on the first try, and typing the same thing differently lands on the row
// that already exists rather than making a second one.
class SkillServiceTest extends IntegrationTestBase {

    @Autowired
    private SkillService skillService;
    @Autowired
    private SkillRepository skillRepository;

    // A skill nobody has ever posted. Nothing to approve, nothing to configure: the row
    // is created on the spot, labelled with the spelling that was just typed.
    @Test
    void anUnseenSkillIsCreatedFromWhatTheUserTyped() {
        assertThat(skillRepository.findBySlug("rust")).isEmpty();

        List<Skill> resolved = skillService.resolve("Rust");

        assertThat(resolved).extracting(Skill::getLabel).containsExactly("Rust");
        assertThat(skillRepository.findBySlug("rust")).isPresent();
    }

    // The same skill typed any other way reuses the existing row, which is what makes
    // identity matching and facet counts work at all - and why a later job that types
    // "JAVA" renders "Java" (the label of the row it joined).
    @Test
    void anySpellingOfAnExistingSkillReusesItsRow() {
        Skill seeded = skillRepository.findBySlug("java").orElseThrow();

        for (String typed : List.of("JAVA", "  java  ", "Java")) {
            List<Skill> resolved = skillService.resolve(typed);
            assertThat(resolved).hasSize(1);
            assertThat(resolved.get(0).getId()).as("typed as '%s'", typed).isEqualTo(seeded.getId());
            assertThat(resolved.get(0).getLabel()).isEqualTo("Java");
        }
    }

    // Punctuation and spacing are identity, not spelling: these are one skill, and the
    // second and third spellings must not create rows of their own.
    @Test
    void punctuationVariantsResolveToOneRow() {
        List<Skill> first = skillService.resolve("Node.js");
        List<Skill> second = skillService.resolve("node js");
        List<Skill> third = skillService.resolve("NODE-JS");

        assertThat(second.get(0).getId()).isEqualTo(first.get(0).getId());
        assertThat(third.get(0).getId()).isEqualTo(first.get(0).getId());
        assertThat(skillRepository.findAll()).filteredOn(skill -> skill.getSlug().equals("node js")).hasSize(1);
    }

    // Order is kept, duplicates collapse, blanks disappear - SkillParser's rules, reaching
    // the database unchanged.
    @Test
    void theTypedOrderIsKeptAndDuplicatesCollapse() {
        List<Skill> resolved = skillService.resolve("Kotlin, Rust ,, kotlin,  , Elixir");

        assertThat(resolved).extracting(Skill::getLabel).containsExactly("Kotlin", "Rust", "Elixir");
    }

    @Test
    void blankInputResolvesToNoSkillsAtAll() {
        assertThat(skillService.resolve(null)).isEmpty();
        assertThat(skillService.resolve("   ")).isEmpty();
        assertThat(skillService.resolve(",,,")).isEmpty();
    }
}
