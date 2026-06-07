package com.transportadora.chaos.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;

/**
 * São Paulo's vehicle rotation ("rodízio municipal") rules. Restriction applies
 * on weekdays during peak windows (07:00–10:00 and 17:00–20:00) inside the
 * Centro Expandido, keyed off the last digit of the plate:
 *
 * <pre>
 *   Monday    → 1, 2
 *   Tuesday   → 3, 4
 *   Wednesday → 5, 6
 *   Thursday  → 7, 8
 *   Friday    → 9, 0
 * </pre>
 *
 * This mirrors the dbt seed {@code rodizio_schedule.csv}; the two must agree.
 */
@Component
public class RodizioCalendar {

    public static final int MORNING_PEAK_START = 7;
    public static final int MORNING_PEAK_END   = 10;  // exclusive
    public static final int EVENING_PEAK_START = 17;
    public static final int EVENING_PEAK_END   = 20;  // exclusive

    /** Last digit of the plate (plates always end in a digit here). */
    public int lastDigit(String plate) {
        char c = plate.charAt(plate.length() - 1);
        return c - '0';
    }

    /** The weekday on which the given plate is restricted. */
    public DayOfWeek restrictedDayFor(String plate) {
        return switch (lastDigit(plate)) {
            case 1, 2 -> DayOfWeek.MONDAY;
            case 3, 4 -> DayOfWeek.TUESDAY;
            case 5, 6 -> DayOfWeek.WEDNESDAY;
            case 7, 8 -> DayOfWeek.THURSDAY;
            case 9, 0 -> DayOfWeek.FRIDAY;
            default -> throw new IllegalArgumentException("not a digit: " + plate);
        };
    }

    public boolean isPeakHour(OffsetDateTime t) {
        int h = t.getHour();
        return (h >= MORNING_PEAK_START && h < MORNING_PEAK_END)
                || (h >= EVENING_PEAK_START && h < EVENING_PEAK_END);
    }

    /**
     * The most recent instant within [historyStart, now] that falls on the
     * plate's restricted weekday, set to a random evening-peak time. Used to
     * pin a forced violation onto a day where the plate is genuinely blocked.
     */
    public OffsetDateTime mostRecentRestrictedPeakInstant(String plate,
                                                          OffsetDateTime historyStart,
                                                          OffsetDateTime now) {
        DayOfWeek target = restrictedDayFor(plate);
        OffsetDateTime cursor = now;
        while (cursor.getDayOfWeek() != target || cursor.isBefore(historyStart)) {
            cursor = cursor.minusDays(1);
            if (cursor.isBefore(historyStart)) {
                // Walk forward instead if we ran past the window's start.
                cursor = historyStart;
                while (cursor.getDayOfWeek() != target) {
                    cursor = cursor.plusDays(1);
                }
                break;
            }
        }
        int hour = EVENING_PEAK_START + (int) (Math.random() * (EVENING_PEAK_END - EVENING_PEAK_START));
        int minute = (int) (Math.random() * 60);
        return cursor.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }
}
