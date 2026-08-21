package org.example.carrental.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A half-open interval of time: {@code startAt} inclusive, {@code endAt} exclusive.
 * <p>
 * Distinct from {@link RentalPeriod}, which is what a customer books - a start plus a whole
 * number of days. A span is the more general shape needed once an interval is clipped to a
 * calendar day, because {@code Mon 10:00 - Tue 00:00} is neither a whole number of days nor a
 * booking.
 * <p>
 * Half-open is what makes a car returned at 11:00 available to a rental starting at 11:00.
 */
public record TimeSpan(LocalDateTime startAt, LocalDateTime endAt) {

    public TimeSpan {
        if (startAt == null || endAt == null) {
            throw new IllegalArgumentException("A time span needs both a start and an end");
        }
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("A time span must end after it starts: " + startAt + " to " + endAt);
        }
    }

    public static TimeSpan of(LocalDateTime startAt, LocalDateTime endAt) {
        return new TimeSpan(startAt, endAt);
    }

    /**
     * The whole of one calendar day, midnight to midnight.
     */
    public static TimeSpan wholeDay(LocalDate date) {
        return new TimeSpan(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    public boolean overlaps(TimeSpan other) {
        return startAt.isBefore(other.endAt) && other.startAt.isBefore(endAt);
    }

    /**
     * The part of this span that falls inside {@code other}, empty when they do not overlap.
     */
    public Optional<TimeSpan> intersect(TimeSpan other) {
        LocalDateTime start = startAt.isAfter(other.startAt) ? startAt : other.startAt;
        LocalDateTime end = endAt.isBefore(other.endAt) ? endAt : other.endAt;
        return end.isAfter(start) ? Optional.of(new TimeSpan(start, end)) : Optional.empty();
    }

    /**
     * Every calendar day this span touches, in order. A span ending exactly at midnight does
     * not touch the day that begins there.
     */
    public List<LocalDate> datesTouched() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate last = endAt.minusNanos(1).toLocalDate();
        for (LocalDate date = startAt.toLocalDate(); !date.isAfter(last); date = date.plusDays(1)) {
            dates.add(date);
        }
        return List.copyOf(dates);
    }

    @Override
    public String toString() {
        return "[" + startAt + " -> " + endAt + ")";
    }
}
