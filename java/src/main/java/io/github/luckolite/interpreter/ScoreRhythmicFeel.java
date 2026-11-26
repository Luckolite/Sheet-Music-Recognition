// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Score-wide eighth-note feel changes anchored to their printed barlines. */
public final class ScoreRhythmicFeel {
    private static final String SOURCE = "printed-eighth-feel";
    private static final Pattern SWING = Pattern.compile(
            "(?:swing\\s+(?:8ths?|eighths?)(?:\\s+[a-z♩♪♫.]?\\s*=\\s*\\d{2,3})?"
                    + "|rock\\s+swing|(?:back|return)\\s+to\\s+swing)[.!]?");
    private static final Pattern STRAIGHT = Pattern.compile(
            "(?:play\\s+straight|straight\\s+(?:8ths?|eighths?))[.!]?");

    public enum Feel {
        SWING("eighth-feel-v1:swing"),
        STRAIGHT("eighth-feel-v1:straight");

        private final String qualifier;

        Feel(String qualifier) {
            this.qualifier = qualifier;
        }
    }

    private ScoreRhythmicFeel() {}

    private static Feel parse(String printed) {
        String text = printed.strip().toLowerCase(Locale.ROOT);
        if (SWING.matcher(text).matches()) return Feel.SWING;
        if (STRAIGHT.matcher(text).matches()) return Feel.STRAIGHT;
        return null;
    }

    /** Validate the original caption when loading a persisted or projected direction. */
    public static Optional<Feel> read(ScoreExpressiveEvent event) {
        if (event.kind() != ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION
                || event.scope() != ScoreExpressiveEvent.Scope.SCORE
                || event.start().filter(anchor -> anchor.quarterBeatOffset() == 0).isEmpty())
            return Optional.empty();
        for (var evidence : event.evidence()) {
            if (!SOURCE.equals(evidence.sourceId())) continue;
            var feel = parse(evidence.printedText());
            if (feel != null && feel.qualifier.equals(event.qualifierText())) return Optional.of(feel);
        }
        return Optional.empty();
    }

    static List<ScoreExpressiveEvent> capture(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs, int width, int height) {
        var changes = new TreeMap<Integer, ScoreExpressiveEvent>();
        var ambiguous = new HashSet<Integer>();
        for (var word : words) {
            if (word == null || word.text() == null) continue;
            var feel = parse(word.text());
            if (feel == null || word.left() < 0 || word.right() > 1
                    || word.bottom() > 1 || word.top() < 0
                    || !(word.left() < word.right()) || !(word.top() < word.bottom())) continue;
            var binding = barline(score.measures(), staffs, word, width, height);
            if (binding == null || ambiguous.contains(binding.measure())) continue;
            var staff = binding.staff();
            var event = new ScoreExpressiveEvent(
                    SOURCE + ":" + binding.measure() + ":" + feel.name(),
                    ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                    Optional.of(new ScoreAnchor(binding.measure(), 0)), Optional.empty(),
                    ScoreExpressiveEvent.Scope.SCORE, staff.index(), staff.count(),
                    Optional.empty(), ScoreExpressiveEvent.Strength.UNSPECIFIED, feel.qualifier,
                    List.of(new ScoreExpressiveEvent.Evidence(
                            SOURCE, 0, word.left(), staff.index(), staff.count(), word.text())));
            var previous = changes.putIfAbsent(binding.measure(), event);
            if (previous != null && !previous.qualifierText().equals(event.qualifierText())) {
                changes.remove(binding.measure());
                ambiguous.add(binding.measure());
            }
        }
        return List.copyOf(changes.values());
    }

    private record Binding(int measure, PlayingTechniqueDetector.Staff staff, float distance) {}

    private static Binding barline(
            List<MeasureRegion> measures, List<PlayingTechniqueDetector.Staff> staffs,
            PlayingTechniqueDetector.Word word, int width, int height) {
        float left = word.left() * width, bottom = word.bottom() * height;
        Binding best = null;
        for (var staff : staffs) {
            // A feel caption is placed above the top staff of its own system.
            if (staff.index() != 0 || word.top() * height >= staff.top()
                    || bottom > staff.top() + staff.gap() * .35f
                    || staff.top() - bottom > staff.gap() * 6) continue;
            float center = (staff.top() + staff.bottom()) * .5f / height;
            for (int measure = 0; measure < measures.size(); measure++) {
                var bar = measures.get(measure);
                if (center < bar.top() || center > bar.bottom()) continue;
                float offset = left - bar.left() * width;
                float leftLeeway = staff.gap() * 6;
                float rightLeeway = Math.min(staff.gap() * 2,
                        (bar.right() - bar.left()) * width * .25f);
                if (offset < -leftLeeway || offset > rightLeeway) continue;
                float distance = Math.abs(offset) + Math.abs(staff.top() - bottom);
                if (best == null || distance < best.distance())
                    best = new Binding(measure, staff, distance);
            }
        }
        return best;
    }

    public record Interval(double start, double end) {}

    private record Pulse(int measure, int staff, int index) {}

    private static final class Pair {
        final ArrayList<ScoreNavigationNoteProjection.SourceNote> downbeat = new ArrayList<>();
        final ArrayList<ScoreNavigationNoteProjection.SourceNote> offbeat = new ArrayList<>();
        boolean downbeatRest;
        boolean offbeatRest;
    }

    /** Realize written pairs on a 2:1 grid while keeping the quarter-beat boundaries. */
    public static Map<Integer, Interval> swingPairs(
            List<ScoreNavigationNoteProjection.SourceNote> sounding, List<ScoreNoteEvent> written,
            ScoreMeterMap meter, List<ScoreExpressiveEvent> expressions) {
        var changes = new TreeMap<Integer, Feel>();
        for (var event : expressions)
            read(event).ifPresent(feel -> changes.merge(event.start().orElseThrow().measureIndex(),
                    feel, (old, next) -> old == next ? old : Feel.STRAIGHT));
        if (!changes.containsValue(Feel.SWING)) return Map.of();
        var pulses = new HashMap<Pulse, Pair>();
        for (var sound : sounding) {
            var note = written.get(sound.sourceIndex());
            var feel = changes.floorEntry(note.measureIndex());
            if (feel == null || feel.getValue() != Feel.SWING || note.tupletDivisor() != 1
                    || note.tiedFromPrevious() || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            double barStart = meter.startBeat(note.measureIndex());
            double barLength = meter.beatsInMeasure(note.measureIndex());
            double start = sound.startBeat() - barStart, end = sound.endBeat() - barStart;
            if (near(note.leadingRestBeats(), .5)) addRest(pulses, note, start - .5, barLength);
            if (near(note.followingRestBeats(), .5)) addRest(pulses, note, end, barLength);
            if (note.augmentationDots() == 0 && near(end - start, .5)) {
                int half = halfBeat(start, barLength);
                if (half >= 0) {
                    var pair = pairAt(pulses, note, half);
                    (half % 2 == 0 ? pair.downbeat : pair.offbeat).add(sound);
                }
            }
        }
        var adjusted = new HashMap<Integer, Interval>();
        for (var entry : pulses.entrySet()) {
            var pair = entry.getValue();
            if (pair.downbeat.isEmpty() && !pair.downbeatRest
                    || pair.offbeat.isEmpty() && !pair.offbeatRest) continue;
            var pulse = entry.getKey();
            double offbeat = meter.startBeat(pulse.measure()) + pulse.index() + 2d / 3;
            for (var note : pair.downbeat)
                adjusted.put(note.sourceIndex(), new Interval(note.startBeat(), offbeat));
            for (var note : pair.offbeat)
                adjusted.put(note.sourceIndex(), new Interval(offbeat, note.endBeat()));
        }
        return Map.copyOf(adjusted);
    }

    private static Pair pairAt(Map<Pulse, Pair> pulses, ScoreNoteEvent note, int half) {
        var pulse = new Pulse(note.measureIndex(), note.staffIndex(), half / 2);
        return pulses.computeIfAbsent(pulse, ignored -> new Pair());
    }

    private static void addRest(
            Map<Pulse, Pair> pulses, ScoreNoteEvent note, double start, double barLength) {
        int half = halfBeat(start, barLength);
        if (half < 0) return;
        var pair = pairAt(pulses, note, half);
        if (half % 2 == 0) pair.downbeatRest = true;
        else pair.offbeatRest = true;
    }

    private static int halfBeat(double start, double barLength) {
        int half = (int) Math.round(start * 2);
        return half >= 0 && near(start, half * .5) && half / 2 + 1 <= barLength + 1e-6
                ? half : -1;
    }

    private static boolean near(double first, double second) {
        return Math.abs(first - second) <= 1e-6;
    }
}
