package com.jobportal.repository;

import com.jobportal.domain.Job;
import com.jobportal.domain.Skill;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

// Implementation of the JobSkillFacets fragment. Spring Data finds it by name:
// <fragment interface> + "Impl" is the convention, so renaming either half breaks the
// wiring silently - JobRepository would still start, and countSkills would throw at the
// first call.
public class JobSkillFacetsImpl implements JobSkillFacets {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<SkillCount> countSkills(Specification<Job> spec, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Job> root = query.from(Job.class);

        // An inner join, so a job with no skills simply contributes nothing - there is no
        // facet for "has no skills".
        Join<Job, Skill> skill = root.join("skills");

        // countDistinct, not count: the Specification is free to add joins of its own
        // (live() reaches through to the employer today, and a future filter might join
        // something to-many), and a join that multiplies rows would inflate every number
        // here without changing the result list beside it - the worst kind of wrong,
        // because the page would look right.
        Expression<Long> jobCount = cb.countDistinct(root.get("id"));

        Predicate where = spec == null ? null : spec.toPredicate(root, query, cb);
        if (where != null) {
            query.where(where);
        }
        query.multiselect(skill.get("slug"), skill.get("label"), jobCount);
        query.groupBy(skill.get("slug"), skill.get("label"));
        query.orderBy(cb.desc(jobCount), cb.asc(skill.get("label")));

        List<SkillCount> counts = new ArrayList<>();
        for (Tuple row : entityManager.createQuery(query).setMaxResults(limit).getResultList()) {
            counts.add(new SkillCount(row.get(0, String.class), row.get(1, String.class),
                    row.get(2, Long.class)));
        }
        return counts;
    }
}
