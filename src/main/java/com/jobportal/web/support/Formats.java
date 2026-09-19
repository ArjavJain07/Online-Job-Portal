package com.jobportal.web.support;

import com.jobportal.domain.Interview;
import com.jobportal.domain.Job;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.stereotype.Component;

// Template helper "@fmt" (Section 7.1): small display formatters shared by every page so
// numbers, dates and file sizes read the same everywhere.
@Component("fmt")
public class Formats {

    private final Clock clock;

    public Formats(Clock clock) {
        this.clock = clock;
    }

    // Indian digit grouping (last 3 digits, then groups of 2): 600000 -> "6,00,000".
    // Written by hand because java.text.DecimalFormat cannot group by lakhs.
    public String inr(long amount) {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder grouped = new StringBuilder();
        int end = digits.length();
        if (end <= 3) {
            grouped.append(digits);
        } else {
            grouped.append(digits, end - 3, end);
            int cursor = end - 3;
            while (cursor > 0) {
                int start = Math.max(0, cursor - 2);
                grouped.insert(0, digits.substring(start, cursor) + ",");
                cursor = start;
            }
        }
        return (amount < 0 ? "-" : "") + grouped;
    }

    public String salaryRange(Job job) {
        return "INR " + inr(job.getSalaryMin()) + " - " + inr(job.getSalaryMax()) + " per year";
    }

    // "3 days ago", "today". Used for approvedAt, which is null only for jobs that have
    // never been approved, so callers only pass it once a job is Live or was Live.
    public String ago(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(dateTime.toLocalDate(), LocalDate.now(clock));
        if (days <= 0) {
            return "today";
        }
        if (days == 1) {
            return "1 day ago";
        }
        return days + " days ago";
    }

    public String fileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public String experience(int minExperienceYears) {
        return minExperienceYears <= 0 ? "Freshers welcome" : minExperienceYears + "+ years";
    }

    // Which of the four things an application's interview currently is, as a word a
    // template can th:switch on: "NONE" (nothing arranged), "UPCOMING", "PAST" or
    // "CANCELLED" (interview scheduling feature).
    //
    // This lives on @fmt rather than on Interview itself because the answer depends on what
    // time it is, and Section 7.10 puts that behind the injected Clock - which this class
    // already holds for exactly the same reason ago(...) does. The alternative, calling
    // ${interview.isUpcoming(...)} from the page, would need "now" passed in as a model
    // attribute by every controller that renders an interview, or a
    // T(java.time.LocalDateTime).now() inside an attribute, which is the construction
    // Thymeleaf 3.1 is unreliable about (see PageLinks.build(Page) and
    // seeker/application-detail.html's own note on notLiveMessage).
    //
    // It also means the seeker's tracking list gets this per row with no extra model
    // attribute and no view-model wrapper: the row already has the Interview, and every
    // page asks the same one question of it in the same one way.
    public String interviewState(Interview interview) {
        if (interview == null) {
            return "NONE";
        }
        if (interview.isCancelled()) {
            return "CANCELLED";
        }
        return interview.isUpcoming(LocalDateTime.now(clock)) ? "UPCOMING" : "PAST";
    }

    // The zone every interview time on this site is entered and shown in, e.g.
    // "Asia/Kolkata" - the injected Clock's, which is also the zone InterviewService stamps
    // onto every row it writes (Section 7.10, and see domain.Interview for why that zone is
    // stored rather than assumed). Used on the employer's scheduling form to say so out
    // loud next to the time field, because an employer typing "3:30" has to know whose 3:30
    // it is before the candidate finds out the hard way.
    public String siteZone() {
        return clock.getZone().getId();
    }
}
