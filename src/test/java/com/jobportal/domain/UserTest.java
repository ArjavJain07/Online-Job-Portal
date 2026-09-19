package com.jobportal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// The company-name search index (Section 10.9, V7). Same reason JobTest covers the two on
// Job: nothing a unit test reads depends on this column, so without a test the setter could
// stop maintaining it and the only symptom would be employers becoming unsearchable under
// their new name - on production, where nobody is looking.
class UserTest {

    @Test
    void settingTheCompanyNameWritesItsSearchIndex() {
        User employer = new User();
        employer.setCompanyName("Acme  Technologies Pvt. Ltd.");

        assertThat(employer.searchCompanyNameIndex()).isEqualTo(" acme technologies pvt ltd ");
        assertThat(employer.needsSearchIndex()).isFalse();
    }

    // A rename has to move the index with it - this is the whole argument for keeping the
    // index on the user row instead of copying the name onto each of the employer's jobs.
    @Test
    void renamingTheCompanyRebuildsItsSearchIndex() {
        User employer = new User();
        employer.setCompanyName("Acme Technologies");
        employer.setCompanyName("Northwind Systems");

        assertThat(employer.searchCompanyNameIndex()).isEqualTo(" northwind systems ");
    }

    // Three states that all look like NULL in the column, and only the middle one is work
    // for SearchIndexService: a user who never had a company name (every job seeker and the
    // admin), a user who has one that has never been indexed, and an employer demoted back
    // to another role, whose name is cleared along with its index.
    @Test
    void onlyAUserWithANameButNoIndexNeedsIndexing() {
        User seeker = new User();
        assertThat(seeker.needsSearchIndex()).isFalse();

        User employer = new User();
        employer.setCompanyName("Acme Technologies");
        assertThat(employer.needsSearchIndex()).isFalse();

        employer.setCompanyName(null);
        assertThat(employer.searchCompanyNameIndex()).isNull();
        assertThat(employer.needsSearchIndex()).isFalse();
    }
}
