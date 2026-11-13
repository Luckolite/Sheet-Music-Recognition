// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import static io.github.luckolite.interpreter.ScoreExpressiveEvent.*;

/** Printed undirected arpeggios retain complete written columns, without performed offsets. */
public final class ScoreArpeggioNotation {
    private static final String PREFIX = "arpeggio-column-v1:";
    private static final String CROSS_PREFIX = "arpeggio-grand-staff-v1:";
    private static final float COLUMN = .018f;

    private ScoreArpeggioNotation() {}

    public record Member(String eventId, int sourceIndex, ScoreAnchor attack) {}

    /** Accept only an independently proved glyph and its complete same-staff column. */
    public static ScorePageInterpretation capture(
            ScorePageInterpretation score, Collection<Integer> sourceIndices, Evidence glyph) {
        return capture(score, sourceIndices, glyph, false);
    }

    static ScorePageInterpretation captureGrandStaff(
            ScorePageInterpretation score, Collection<Integer> sourceIndices, Evidence glyph) {
        return capture(score, sourceIndices, glyph, true);
    }

    private static ScorePageInterpretation capture(
            ScorePageInterpretation score, Collection<Integer> sourceIndices, Evidence glyph,
            boolean grandStaff) {
        var indices = new TreeSet<Integer>(sourceIndices);
        if (indices.size() < 2) return score;
        ScoreNoteEvent first = null;
        var steps = grandStaff ? new TreeSet<String>()
                : new TreeSet<String>(Comparator.comparingInt(Integer::parseInt));
        var staffOwners = new TreeSet<Integer>();
        float minPosition = Float.POSITIVE_INFINITY, maxPosition = Float.NEGATIVE_INFINITY;
        for (int i : indices) {
            if (i < 0 || i >= score.notes().size())
                throw new IllegalArgumentException("Source index outside score");
            var n = score.notes().get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || (n.articulations() & 32768) != 0)
                return score;
            if (first == null) first = n;
            if (n.measureIndex() != first.measureIndex()
                    || !grandStaff && n.staffIndex() != first.staffIndex()
                    || n.staffCount() != first.staffCount()
                    || Math.abs(n.positionInMeasure() - first.positionInMeasure()) > COLUMN
                    || (!grandStaff && n.staffIndex() != glyph.staffIndex())
                    || (grandStaff && (n.staffCount() != 2 || glyph.staffIndex() != 0))
                    || n.staffCount() != glyph.staffCount()) return score;
            staffOwners.add(n.staffIndex());
            steps.add(memberKey(n, grandStaff));
            minPosition = Math.min(minPosition, n.positionInMeasure());
            maxPosition = Math.max(maxPosition, n.positionInMeasure());
        }
        if (grandStaff && !staffOwners.equals(Set.of(0, 1))) return score;
        if (steps.size() != indices.size() || maxPosition - minPosition > COLUMN) return score;
        // Reject partial columns: no head at this optical attack may be silently discarded.
        for (int i = 0; i < score.notes().size(); i++) {
            var n = score.notes().get(i);
            if (n.kind() == ScoreNoteEvent.Kind.PITCHED
                    && (n.articulations() & 32768) == 0
                    && n.measureIndex() == first.measureIndex()
                    && (grandStaff || n.staffIndex() == first.staffIndex())
                    && n.staffCount() == first.staffCount()
                    && Math.abs(n.positionInMeasure() - first.positionInMeasure()) <= COLUMN
                    && !indices.contains(i)) return score;
        }
        String target =
                (grandStaff ? CROSS_PREFIX : PREFIX)
                        + first.measureIndex()
                        + ":"
                        + glyph.staffIndex()
                        + ":"
                        + first.staffCount()
                        + ":"
                        + Float.toHexString(minPosition)
                        + ":"
                        + String.join(",", steps);
        var events = new ArrayList<>(score.expressiveEvents());
        if (events.stream()
                .anyMatch(
                        e ->
                                e.kind() == Kind.ARPEGGIO
                                        && e.targetEventId().orElse("").equals(target)))
            return score;
        events.add(
                new ScoreExpressiveEvent(
                        "printed-" + target,
                        Kind.ARPEGGIO,
                        Optional.empty(),
                        Optional.empty(),
                        Scope.UNRESOLVED,
                        glyph.staffIndex(),
                        first.staffCount(),
                        Optional.of(target),
                        Strength.UNSPECIFIED,
                        "",
                        List.of(glyph)));
        return score.withExpressiveEvents(events);
    }

    private static String memberKey(ScoreNoteEvent note, boolean grandStaff) {
        return grandStaff ? note.staffIndex() + "/" + note.staffStep()
                : Integer.toString(note.staffStep());
    }

    private static String prefix(String target) {
        if (target.startsWith(CROSS_PREFIX)) return CROSS_PREFIX;
        return target.startsWith(PREFIX) ? PREFIX : "";
    }

    /** Re-resolve source identity against the current page; list positions are never serialized. */
    public static List<Integer> targetIndices(
            ScorePageInterpretation score, ScoreExpressiveEvent event) {
        if (event.kind() != Kind.ARPEGGIO
                || event.targetEventId().isEmpty()
                || event.evidence().stream()
                        .noneMatch(e -> e.sourceId().equals("printed-arpeggio-wave")))
            return List.of();
        String target = event.targetEventId().get();
        String prefix = prefix(target);
        if (prefix.isEmpty()) return List.of();
        boolean grandStaff = prefix.equals(CROSS_PREFIX);
        try {
            String[] t = target.substring(prefix.length()).split(":", -1);
            if (t.length != 5) return List.of();
            int measure = Integer.parseInt(t[0]),
                    staff = Integer.parseInt(t[1]),
                    count = Integer.parseInt(t[2]);
            float position = Float.parseFloat(t[3]);
            if (staff != event.staffIndex()
                    || count != event.staffCount()
                    || grandStaff && (staff != 0 || count != 2)
                    || measure < 0
                    || measure >= score.measures().size()
                    || !Float.isFinite(position)
                    || position < 0
                    || position > 1) return List.of();
            var expected = new TreeSet<String>();
            var staffOwners = new TreeSet<Integer>();
            for (String step : t[4].split(",", -1)) {
                if (grandStaff) {
                    String[] member = step.split("/", -1);
                    if (member.length != 2) return List.of();
                    int owner = Integer.parseInt(member[0]);
                    staffOwners.add(owner);
                    if (!expected.add(owner + "/" + Integer.parseInt(member[1]))) return List.of();
                } else if (!expected.add(Integer.toString(Integer.parseInt(step)))) return List.of();
            }
            if (expected.size() < 2 || grandStaff && !staffOwners.equals(Set.of(0, 1)))
                return List.of();
            if (event.evidence().stream()
                    .noneMatch(
                            e ->
                                    e.sourceId().equals("printed-arpeggio-wave")
                                            && e.staffIndex() == staff
                                            && e.staffCount() == count)) return List.of();
            var found = new TreeSet<String>();
            var result = new ArrayList<Integer>();
            for (int i = 0; i < score.notes().size(); i++) {
                var n = score.notes().get(i);
                if (n.kind() == ScoreNoteEvent.Kind.PITCHED
                        && (n.articulations() & 32768) == 0
                        && n.measureIndex() == measure
                        && (grandStaff || n.staffIndex() == staff)
                        && n.staffCount() == count
                        && Math.abs(n.positionInMeasure() - position) <= COLUMN) {
                    if (!found.add(memberKey(n, grandStaff))) return List.of();
                    result.add(i);
                }
            }
            return !found.equals(expected) ? List.of() : List.copyOf(result);
        } catch (IllegalArgumentException malformed) {
            return List.of();
        }
    }

    /** Rebase detector-owned optical identities together with their page/measure evidence. */
    static boolean owns(ScoreExpressiveEvent event) {
        return event.kind() == Kind.ARPEGGIO
                && !prefix(event.targetEventId().orElse("")).isEmpty()
                && event.evidence().stream()
                        .anyMatch(e -> e.sourceId().equals("printed-arpeggio-wave"));
    }

    static ScoreExpressiveEvent offsetEvidence(ScoreExpressiveEvent event, int offset, int page) {
        if (!owns(event)) return event.offset(offset);
        String prefix = prefix(event.targetEventId().get());
        try {
            String[] fields = event.targetEventId().get().substring(prefix.length()).split(":", -1);
            if (fields.length != 5) return event.offset(offset);
            try {
                fields[0] = Integer.toString(Math.addExact(Integer.parseInt(fields[0]), offset));
                return new ScoreExpressiveEvent(
                        "page:" + page + "/" + event.eventId(),
                        event.kind(),
                        event.start().map(a -> a.offset(offset)),
                        event.end().map(a -> a.offset(offset)),
                        event.scope(),
                        event.staffIndex(),
                        event.staffCount(),
                        Optional.of(prefix + String.join(":", fields)),
                        event.strength(),
                        event.qualifierText(),
                        event.evidence().stream()
                                .map(
                                        e ->
                                                new Evidence(
                                                        e.sourceId(),
                                                        page,
                                                        e.visualX(),
                                                        e.staffIndex(),
                                                        e.staffCount(),
                                                        e.printedText()))
                                .toList());
            } catch (NumberFormatException malformed) {
                return event.offset(offset);
            }
        } catch (ArithmeticException overflow) {
            // Internal invalid ownership sentinel: never a valid column, but still detector-owned
            // so symbol-only consumers retain the evidence without generic fallback words.
            return new ScoreExpressiveEvent(
                    "page:" + page + "/" + event.eventId(),
                    event.kind(),
                    Optional.empty(),
                    Optional.empty(),
                    Scope.UNRESOLVED,
                    event.staffIndex(),
                    event.staffCount(),
                    Optional.of(prefix + "invalid-offset:" + event.targetEventId().orElse("")),
                    event.strength(),
                    event.qualifierText(),
                    event.evidence().stream()
                            .map(
                                    e ->
                                            new Evidence(
                                                    e.sourceId(),
                                                    page,
                                                    e.visualX(),
                                                    e.staffIndex(),
                                                    e.staffCount(),
                                                    e.printedText()))
                            .toList());
        }
    }

    /** A resolved mark identifies a simultaneous written attack, not a performed roll. */
    public static ScorePageInterpretation resolve(
            ScorePageInterpretation score, float openingBeats) {
        var all = new ArrayList<Integer>();
        for (int i = 0; i < score.notes().size(); i++) all.add(i);
        var attacks = new HashMap<String, ScoreAnchor>();
        for (var member : plan(score, all, openingBeats))
            attacks.put(member.eventId(), member.attack());
        var events = new ArrayList<ScoreExpressiveEvent>();
        for (var e : score.expressiveEvents()) {
            if (!owns(e)) {
                events.add(e);
                continue;
            }
            var anchor = Optional.ofNullable(attacks.get(e.eventId()));
            events.add(
                    new ScoreExpressiveEvent(
                            e.eventId(),
                            e.kind(),
                            anchor,
                            Optional.empty(),
                            anchor.isPresent() ? Scope.NOTE : Scope.UNRESOLVED,
                            e.staffIndex(),
                            e.staffCount(),
                            e.targetEventId(),
                            e.strength(),
                            e.qualifierText(),
                            e.evidence()));
        }
        return score.withExpressiveEvents(events);
    }

    /** Only a complete available chord receives arpeggiate; no order, direction, or spread is invented. */
    public static List<Member> plan(
            ScorePageInterpretation score, Collection<Integer> available, float openingBeats) {
        return plan(score, available, openingBeats, Map.of());
    }

    /** Retained clocks are keyed by the unchanged full source score, not selected note slots. */
    public static List<Member> plan(
            ScorePageInterpretation score,
            Collection<Integer> available,
            float openingBeats,
            Map<Integer, ScoreNoteTiming.WrittenPlacement> writtenPlacements) {
        var allowed = new HashSet<>(available);
        for (int i : allowed)
            if (i < 0 || i >= score.notes().size())
                throw new IllegalArgumentException("Source index outside score");
        if (!Float.isFinite(openingBeats) || openingBeats <= 0) return List.of();
        var meter = new ScoreMeterMap(openingBeats, score.meterChanges());
        var retained = ScorePlacementMap.validatedWritten(writtenPlacements, score.notes());
        if (!retained.isEmpty()) return planWithClock(score, allowed, meter, retained);
        try (var session = ScoreNoteTiming.beginTimingSession(score)) {
            return planWithClock(score, allowed, meter, Map.of());
        }
    }

    private static List<Member> planWithClock(
            ScorePageInterpretation score,
            Set<Integer> allowed,
            ScoreMeterMap meter,
            Map<Integer, ScoreNoteTiming.WrittenPlacement> retained) {
        var result = new ArrayList<Member>();
        for (var event : score.expressiveEvents()) {
            var indices = targetIndices(score, event);
            if (indices.isEmpty() || !allowed.containsAll(indices)) continue;
            double onset = Double.NaN;
            boolean proved = true;
            for (int index : indices) {
                var n = score.notes().get(index);
                float beats = meter.beatsInMeasure(n.measureIndex());
                var placement = retained.get(index);
                if (!retained.isEmpty() && placement == null)
                    throw new IllegalArgumentException(
                            "A retained arpeggio needs every source member clock");
                double start =
                        placement == null
                                ? ScoreNoteTiming.beatInMeasure(n, score.notes(), beats)
                                : placement.onsetBeats();
                double duration =
                        placement == null
                                ? ScoreNoteTiming.resolvedWrittenDurationBeats(
                                        n, score.notes(), beats)
                                : placement.durationBeats();
                double end = ScoreAnchor.computedOffset(n.measureIndex(), start + duration, meter);
                if (!compatibleMember(start, end, beats, onset)) proved = false;
                onset = start;
            }
            if (!proved) continue;
            var attack =
                    new ScoreAnchor(score.notes().get(indices.get(0)).measureIndex(), onset)
                            .canonical(meter, score.measures().size());
            for (int index : indices)
                if (allowed.contains(index)) result.add(new Member(event.eventId(), index, attack));
        }
        return List.copyOf(result);
    }

    /** Arpeggiation owns a shared attack; independently written releases may differ. */
    static boolean compatibleMember(double start, double end, double measureBeats, double onset) {
        return Double.isFinite(start)
                && Double.isFinite(end)
                && Double.isFinite(measureBeats)
                && start >= 0
                && end > start
                && end <= measureBeats
                && (!Double.isFinite(onset) || Math.abs(start - onset) <= 1e-7);
    }
}
