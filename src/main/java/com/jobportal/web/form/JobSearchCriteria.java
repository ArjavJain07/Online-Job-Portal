package com.jobportal.web.form;

// Raw search filters bound straight from the query string of GET /jobs (and, from M5, GET
// /seeker/jobs) - Section 6.4 S-F1. Every field is a plain String and nothing here is
// validated: JobSearchService.normalise() parses and checks each one, so a typo in the
// URL (for example minSalary=abc) never produces a 400 error page (Section 7.9 binding
// rule). Keeping the raw strings also lets the search form redisplay exactly what the
// visitor typed, even the parts that turned out to be invalid.
public class JobSearchCriteria {

    private String q;
    private String location;
    private String category;
    private String jobType;
    private String workMode;
    private String minSalary;
    private String maxExperience;
    private String postedWithin;
    // The selected skill facet, carried as a canonical slug (Section 10.8). A String like
    // every other filter here, so "skill=does-not-exist" falls back to "any skill"
    // instead of reaching the type-mismatch 404 handler (Section 7.9 binding rule).
    private String skill;
    private String sort;
    private String page;

    public String getQ() {
        return q;
    }

    public void setQ(String q) {
        this.q = q;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getWorkMode() {
        return workMode;
    }

    public void setWorkMode(String workMode) {
        this.workMode = workMode;
    }

    public String getMinSalary() {
        return minSalary;
    }

    public void setMinSalary(String minSalary) {
        this.minSalary = minSalary;
    }

    public String getMaxExperience() {
        return maxExperience;
    }

    public void setMaxExperience(String maxExperience) {
        this.maxExperience = maxExperience;
    }

    public String getPostedWithin() {
        return postedWithin;
    }

    public void setPostedWithin(String postedWithin) {
        this.postedWithin = postedWithin;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public String getSort() {
        return sort;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public String getPage() {
        return page;
    }

    public void setPage(String page) {
        this.page = page;
    }
}
