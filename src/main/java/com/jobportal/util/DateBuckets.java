package com.jobportal.util;

import com.jobportal.dto.ChartData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// Groups timestamps into a Chart.js-ready series over a 7, 30 or 90 day window
// (Section 7.6). Grouping happens in Java, not SQL, because H2 and MySQL do not agree on
// date functions and the demo data volumes are small.
public final class DateBuckets {

    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMM");

    private DateBuckets() {
    }

    // Parses the days request parameter: only 7, 30 or 90 are accepted, anything else
    // (missing, blank, non-numeric, or a different number) falls back to 30.
    public static int normaliseDays(String raw) {
        if (raw == null) {
            return 30;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value == 7 || value == 30 || value == 90) ? value : 30;
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    // Buckets timestamps into a series: daily buckets for 7 or 30 days (labelled "16
    // Sep"), or 13 weekly buckets for 90 days (labelled with the week's first day, the
    // last week ending today). Empty buckets stay 0.
    public static ChartData count(List<LocalDateTime> timestamps, LocalDate today, int days, String seriesLabel) {
        List<LocalDate> bucketStarts = bucketStarts(today, days);
        int bucketLengthDays = days == 90 ? 7 : 1;
        long[] counts = new long[bucketStarts.size()];

        for (LocalDateTime timestamp : timestamps) {
            int index = bucketIndex(bucketStarts, bucketLengthDays, timestamp.toLocalDate());
            if (index >= 0) {
                counts[index]++;
            }
        }

        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < bucketStarts.size(); i++) {
            labels.add(bucketStarts.get(i).format(LABEL_FORMAT));
            values.add(counts[i]);
        }
        return new ChartData(seriesLabel, labels, values);
    }

    // The first day of each bucket, oldest first; the last bucket always ends today.
    private static List<LocalDate> bucketStarts(LocalDate today, int days) {
        List<LocalDate> starts = new ArrayList<>();
        if (days == 90) {
            LocalDate firstBucketStart = today.minusDays(90);
            for (int i = 0; i < 13; i++) {
                starts.add(firstBucketStart.plusDays((long) i * 7));
            }
        } else {
            LocalDate firstDay = today.minusDays(days - 1L);
            for (int i = 0; i < days; i++) {
                starts.add(firstDay.plusDays(i));
            }
        }
        return starts;
    }

    private static int bucketIndex(List<LocalDate> bucketStarts, int bucketLengthDays, LocalDate date) {
        for (int i = bucketStarts.size() - 1; i >= 0; i--) {
            LocalDate start = bucketStarts.get(i);
            LocalDate end = start.plusDays(bucketLengthDays - 1L);
            if (!date.isBefore(start) && !date.isAfter(end)) {
                return i;
            }
        }
        return -1;
    }
}
