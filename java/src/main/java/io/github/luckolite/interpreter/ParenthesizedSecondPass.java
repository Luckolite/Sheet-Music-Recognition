// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Keeps a parenthesized second-traversal passage inside its printed measure. */
final class ParenthesizedSecondPass {
    static final String BOTTOM_SOURCE = "printed-bottom-note-second-pass:v1";

    private ParenthesizedSecondPass() {}

    static List<MeasureRegion> join(List<MeasureRegion> bars, List<PlayingTechniqueDetector.Word> words,
            byte[] gray, byte[] source, int width, int height) {
        if (gray == null || source == null || gray.length != (long) width * height
                || source.length != gray.length) return bars;
        var instructions = words.stream().filter(ParenthesizedSecondPass::secondOnly).toList();
        if (instructions.isEmpty()) return bars;
        List<MeasureRegion> merged = null;
        for (int i = 0; i < bars.size(); i++) {
            var bar = bars.get(i);
            if (i + 1 < bars.size() && closesInsideBar(
                    bar, bars.get(i + 1), instructions, gray, source, width, height)) {
                if (merged == null) merged = new ArrayList<>(bars.subList(0, i));
                var next = bars.get(++i);
                merged.add(new MeasureRegion(bar.left(), next.right(), bar.top(), bar.bottom()));
            } else if (merged != null) {
                merged.add(bar);
            }
        }
        return merged == null ? bars : List.copyOf(merged);
    }

    static boolean hasGeometry(List<MeasureRegion> bars, byte[] gray, int width, int height) {
        for (int i = 0; i + 1 < bars.size(); i++) {
            var bar = bars.get(i);
            var next = bars.get(i + 1);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (!adjacent(bar, next, gap, width, height)) continue;
            if (curves(gray, width, height, bar.left() * width + .45f * gap,
                    (bar.right() + next.left()) * .5f * width, bar.bottom() * height - 2.1f * gap, gap))
                return true;
        }
        return false;
    }

    private static boolean adjacent(MeasureRegion bar, MeasureRegion next, float gap, int width, int height) {
        return gap >= 10 && gap <= 26 && Math.abs(bar.top() - next.top()) * height <= 2
                && Math.abs(bar.bottom() - next.bottom()) * height <= 2 && bar.right() < next.left()
                && (next.left() - bar.right()) * width <= 1.6f * gap
                && bar.cadenceQuarterBeats() == 0 && next.cadenceQuarterBeats() == 0;
    }

    private static boolean secondOnly(PlayingTechniqueDetector.Word word) {
        String text = word.text().toLowerCase(Locale.ROOT).strip()
                .replaceFirst("^\\(\\s*", "").replaceFirst("\\s*\\)$", "").replaceAll("\\s+", " ");
        return text.equals("2nd time only") || text.equals("second time only");
    }

    private static boolean closesInsideBar(MeasureRegion bar, MeasureRegion next,
            List<PlayingTechniqueDetector.Word> instructions, byte[] gray, byte[] source, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float x0 = bar.left() * width;
        float split = (bar.right() + next.left()) * .5f * width;
        float expectedBottom = bar.bottom() * height - 2.1f * gap;
        if (!adjacent(bar, next, gap, width, height)) return false;
        for (var word : instructions) {
            if (!instructionAbove(word, x0, split, expectedBottom, gap, width, height)) continue;
            if (curves(gray, width, height, x0 + .45f * gap, split, expectedBottom, gap)
                    && (gray == source || curves(source, width, height, x0 + .45f * gap, split, expectedBottom, gap)))
                return true;
        }
        return false;
    }

    private static boolean curves(byte[] pixels, int width, int height,
            float left, float right, float expectedBottom, float gap) {
        var rails = PrintedDirectionStaff.printedRules(
                pixels, width, height, right - 2 * gap, expectedBottom, gap);
        if (rails == null || Math.abs(rails[0] - expectedBottom) > .4f * gap) return false;
        float bottom = rails[0], step = rails[1];
        return bowed(pixels, width, height, left, bottom, step, -1)
                && bowed(pixels, width, height, right, bottom, step, 1);
    }

    private static boolean bowed(byte[] pixels, int width, int height,
            float x, float bottom, float gap, int outward) {
        int topX = narrowInk(pixels, width, height, x, bottom - 3.5f * gap, gap);
        int midX = narrowInk(pixels, width, height, x, bottom - 1.5f * gap, gap);
        int lowX = narrowInk(pixels, width, height, x, bottom + .48f * gap, gap);
        if (topX < 0 || midX < 0 || lowX < 0 || Math.abs(topX - lowX) > .45f * gap) return false;
        int topBend = outward * (midX - topX), lowBend = outward * (midX - lowX);
        return topBend >= .16f * gap && lowBend >= .16f * gap
                && topBend <= .72f * gap && lowBend <= .72f * gap;
    }

    private static int narrowInk(byte[] pixels, int width, int height, float x, float row, float gap) {
        int y = Math.round(row), a = Math.round(x - .57f * gap), b = Math.round(x + .57f * gap);
        if (a < 1 || b >= width - 1 || y < 0 || y >= height) return -1;
        int first = -1, last = -1;
        for (int i = a; i <= b; i++) {
            if ((pixels[y * width + i] & 255) >= 150) continue;
            if (last >= 0 && i > last + 1) return -1;
            if (first < 0) first = i;
            last = i;
        }
        return first < 0 || last - first >= .4f * gap ? -1 : (first + last) / 2;
    }

    private static final String SOURCE = "printed-second-pass-only:v1";

    static ScorePageInterpretation capture(ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words, byte[] gray, byte[] source,
            int width, int height, int page) {
        if (page < 0 || gray == null || source == null || gray.length != (long) width * height
                || source.length != gray.length) return score;
        var instructions = words.stream().filter(ParenthesizedSecondPass::secondOnly).toList();
        if (instructions.isEmpty()) return score;
        var expressions = new ArrayList<>(score.expressiveEvents());
        for (var beam : score.beamSource().groups()) {
            var owners = beam.columns().stream().map(ScoreBeamSource.Column::members).toList();
            if (owners.size() < 2 || owners.stream().anyMatch(column ->
                    column.size() != 1 || column.get(0).pageIndex() != page
                            || column.get(0).noteIndex() >= score.notes().size())) continue;
            var notes = owners.stream().map(c -> score.notes().get(c.get(0).noteIndex())).toList();
            var first = notes.get(0);
            var last = notes.get(notes.size() - 1);
            int m = first.measureIndex();
            if (m >= score.measures().size() || notes.stream().anyMatch(note ->
                    note.measureIndex() != m || note.staffIndex() != first.staffIndex()
                            || note.staffCount() != 1 || note.tiedFromPrevious()
                            || note.kind() != ScoreNoteEvent.Kind.PITCHED
                            || (note.articulations() & NoteOrnament.GRACE) != 0)) continue;
            var bar = score.measures().get(m);
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 26) continue;
            float x0 = bar.left() * width;
            float xFirst = noteX(bar, first, width);
            if (xFirst - x0 < .25f * gap || xFirst - x0 > 2 * gap) continue;
            var next = score.notes().stream().filter(note -> note.measureIndex() == m
                    && note.staffIndex() == first.staffIndex()
                    && note.positionInMeasure() > last.positionInMeasure())
                    .min(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).orElse(null);
            if (next == null) continue;
            float xLast = noteX(bar, last, width);
            float xNext = noteX(bar, next, width);
            if (xNext - xLast < 1.2f * gap || xNext - xLast > 6 * gap) continue;
            float split = xLast + (xNext - xLast) * .6f;
            float bottom = bar.bottom() * height - 2.1f * gap;
            var word = instructions.stream().filter(w ->
                    instructionAbove(w, x0, split, bottom, gap, width, height)).findFirst().orElse(null);
            if (word == null || !curves(gray, width, height, x0 + .45f * gap, split, bottom, gap)
                    || gray != source && !curves(source, width, height, x0 + .45f * gap, split, bottom, gap)) continue;
            String positions = notes.stream().map(ParenthesizedSecondPass::positionKey)
                    .collect(java.util.stream.Collectors.joining(","));
            String id = SOURCE + ":" + page + ":" + m + ":" + first.staffIndex();
            if (expressions.stream().anyMatch(event -> event.eventId().equals(id))) continue;
            expressions.add(new ScoreExpressiveEvent(id, ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                    Optional.of(new ScoreAnchor(m, 0)), Optional.of(new ScoreAnchor(m + 1, 0)),
                    ScoreExpressiveEvent.Scope.UNRESOLVED, first.staffIndex(), first.staffCount(),
                    Optional.of(positions), ScoreExpressiveEvent.Strength.UNSPECIFIED, "",
                    List.of(new ScoreExpressiveEvent.Evidence(SOURCE, page, word.left(),
                            first.staffIndex(), first.staffCount(), word.text()))));
        }
        return expressions.size() == score.expressiveEvents().size() ? score : score.withExpressiveEvents(expressions);
    }

    private static boolean instructionAbove(PlayingTechniqueDetector.Word word, float x0, float split,
            float bottom, float gap, int width, int height) {
        float wordY = word.bottom() * height;
        return Math.abs(word.left() * width - x0) <= 2 * gap && word.right() * width <= split - gap
                && wordY >= bottom - 7.4f * gap && wordY <= bottom - 4.5f * gap;
    }

    private static float noteX(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }

    private static String positionKey(ScoreNoteEvent note) {
        return Integer.toString(Float.floatToIntBits(note.positionInMeasure()));
    }

    static ScorePageInterpretation captureBottomNotes(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, int width, int height, int page) {
        var events = new ArrayList<>(score.expressiveEvents());
        for (var word : words) {
            String text = word.text().toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
            if (!text.matches("play (the )?bottom notes? (2nd|second) time only")) continue;
            var owner = staffs.stream().filter(staff -> staff.gap() >= 6
                            && staff.top() >= word.bottom() * height
                            && staff.top() - word.bottom() * height <= 3.5f * staff.gap())
                    .min(Comparator.comparingDouble(PlayingTechniqueDetector.Staff::top)).orElse(null);
            if (owner == null) continue;
            float gap = owner.gap();
            float staffCenter = (owner.top() + owner.bottom()) / (2 * height);
            for (int m = 0; m < score.measures().size(); m++) {
                var bar = score.measures().get(m);
                if (staffCenter < bar.top() || staffCenter > bar.bottom()
                        || Math.abs(bar.left() - word.left()) * width > gap) continue;
                var notes = bottomNotesInOpeningPattern(score.notes(), m, bar, owner, width);
                if (notes.size() < 2) continue;
                String id = BOTTOM_SOURCE + ":" + page + ":" + m + ":" + owner.index();
                if (events.stream().anyMatch(event -> event.eventId().equals(id))) continue;
                String positions = notes.stream().map(ParenthesizedSecondPass::headKey)
                        .collect(java.util.stream.Collectors.joining(","));
                events.add(new ScoreExpressiveEvent(id, ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                        Optional.of(new ScoreAnchor(m, 0)), Optional.of(new ScoreAnchor(m + 1, 0)),
                        ScoreExpressiveEvent.Scope.NOTE, owner.index(), owner.count(),
                        Optional.of(positions), ScoreExpressiveEvent.Strength.UNSPECIFIED, "",
                        List.of(new ScoreExpressiveEvent.Evidence(BOTTOM_SOURCE, page, word.left(),
                                owner.index(), owner.count(), word.text()))));
            }
        }
        return events.size() == score.expressiveEvents().size() ? score : score.withExpressiveEvents(events);
    }

    private static List<ScoreNoteEvent> bottomNotesInOpeningPattern(
            List<ScoreNoteEvent> source, int measure, MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff, int width) {
        var notes = source.stream().filter(note -> note.measureIndex() == measure
                        && note.staffIndex() == staff.index() && note.staffCount() == staff.count())
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
        float gap = staff.gap();
        var lows = new ArrayList<ScoreNoteEvent>();
        ScoreNoteEvent referenceUpper = null;
        for (int head = 0; head < notes.size();) {
            var first = notes.get(head);
            float x = noteX(bar, first, width);
            int end = head + 1;
            while (end < notes.size() && noteX(bar, notes.get(end), width) - x <= gap * .22f) end++;
            int size = end - head;
            if (size > 2 || (head == 0 && (size != 2 || x - bar.left() * width > 2.5f * gap)))
                return List.of();
            if (size == 2) {
                var second = notes.get(head + 1);
                var lower = first.staffStep() < second.staffStep() ? first : second;
                var upper = lower == first ? second : first;
                if (!plainDyadHead(lower) || !plainDyadHead(upper)
                        || lower.stemDirection() != upper.stemDirection()
                        || upper.staffStep() - lower.staffStep() < 2
                        || (referenceUpper != null && (upper.staffStep() != referenceUpper.staffStep()
                                || lower.staffStep() != lows.get(0).staffStep()))) return List.of();
                if (referenceUpper == null) referenceUpper = upper;
                lows.add(lower);
            }
            head = end;
        }
        return lows;
    }

    private static boolean plainDyadHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.beamCount() == 1
                && note.stemDirection() != 0 && !note.crossStaffBeam() && !note.tiedFromPrevious()
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static String headKey(ScoreNoteEvent note) {
        return positionKey(note) + ":" + note.staffStep();
    }

    static ScoreNavigationNoteProjection.Result project(
            ScoreNavigationNoteProjection.Result performance, List<ScoreExpressiveEvent> expressions,
            List<ScoreNoteEvent> notes) {
        var muted = new HashSet<String>();
        for (var event : expressions) {
            boolean bottom = event.eventId().startsWith(BOTTOM_SOURCE + ":");
            String source = bottom ? BOTTOM_SOURCE : SOURCE;
            if (!event.eventId().startsWith(source + ":")
                    || (bottom && event.scope() != ScoreExpressiveEvent.Scope.NOTE)
                    || event.kind() != ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                    || event.evidence().size() != 1 || !event.evidence().get(0).sourceId().equals(source)
                    || event.start().isEmpty() || event.end().isEmpty() || event.targetEventId().isEmpty()) continue;
            int bar = event.start().get().measureIndex();
            if (event.start().get().quarterBeatOffset() != 0
                    || !event.end().get().equals(new ScoreAnchor(bar + 1, 0))) continue;
            var keys = new HashSet<>(List.of(event.targetEventId().get().split(",")));
            if (keys.size() < 2) continue;
            var sourceNotes = new HashMap<String, Integer>();
            for (int i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                if (note.measureIndex() != bar || note.staffIndex() != event.staffIndex()
                        || note.staffCount() != event.staffCount()) continue;
                String key = bottom ? headKey(note) : positionKey(note);
                if (keys.contains(key)) sourceNotes.merge(key, i, (first, second) -> -1);
            }
            if (sourceNotes.size() != keys.size() || sourceNotes.containsValue(-1)) continue;
            var indices = new HashSet<>(sourceNotes.values());
            var visits = new HashMap<Integer, List<ScoreNavigationNoteProjection.PerformedNote>>();
            for (var note : performance.notes())
                if (indices.contains(note.sourceIndex()) && !note.clippedEntry())
                    visits.computeIfAbsent(note.sourceIndex(), ignored -> new ArrayList<>()).add(note);
            if (visits.size() != keys.size() || visits.values().stream().anyMatch(list -> list.size() != 2)) continue;
            var first = new ArrayList<String>();
            var occurrences = new HashSet<String>();
            var secondOccurrences = new HashSet<String>();
            for (var visitsToNote : visits.values()) {
                visitsToNote.sort(Comparator.comparingDouble(ScoreNavigationNoteProjection.PerformedNote::startBeat));
                var attack = visitsToNote.get(0);
                var repeat = visitsToNote.get(1);
                if (attack.navigationOccurrences().isEmpty() || repeat.navigationOccurrences().isEmpty()) break;
                secondOccurrences.add(repeat.navigationOccurrences().get(0));
                occurrences.add(attack.navigationOccurrences().get(0));
                first.add(attack.performanceId());
            }
            if (first.size() == keys.size() && occurrences.size() == 1 && secondOccurrences.size() == 1
                    && !occurrences.equals(secondOccurrences)) muted.addAll(first);
        }
        if (muted.isEmpty()) return performance;
        return new ScoreNavigationNoteProjection.Result(performance.notes().stream()
                .filter(note -> !muted.contains(note.performanceId())).toList(), performance.performedBeats());
    }

}
