// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Resolve unnumbered eighth-note triplets when the complete mixed-beam bar closes the clock. */
final class ClosedArpeggioTriplets {
    private ClosedArpeggioTriplets() {}

    private static final List<Integer> GROUP_STARTS = List.of(0, 3, 6, 10, 14);

    static ScorePageInterpretation restore(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, byte[] raw, int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var bars = score.measures();
        var notes = score.notes();
        var beams = score.beamSource();
        if (beams.noteOrigins().size() != notes.size()) return score;
        var rows = new ArrayList<List<Integer>>(bars.size());
        for (int i = 0; i < bars.size(); i++) rows.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) rows.get(notes.get(i).measureIndex()).add(i);
        var hasRest = new java.util.BitSet(bars.size());
        for (var rest : score.rests()) hasRest.set(rest.measureIndex());
        var meter = initial;
        int nextMeter = 0;
        List<ScoreNoteEvent> repaired = null;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var row = rows.get(i);
            var bar = bars.get(i);
            if (meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || row.size() != 14 || hasRest.get(i) || bar.cadenceQuarterBeats() != 0) continue;
            row.sort(Comparator.comparingDouble(j -> notes.get(j).positionInMeasure()));
            var music = row.stream().map(notes::get).toList();
            if (!matchesNotes(music) || !matchesArpeggio(music)
                    || !provesRails(gray, width, height, bar, music)
                    || !provesRails(raw, width, height, bar, music)) continue;
            var updatedBeams = replaceGroups(beams, row);
            if (updatedBeams == beams) continue;
            beams = updatedBeams;
            if (repaired == null) repaired = new ArrayList<>(notes);
            for (int j = 0; j < 6; j++)
                repaired.set(row.get(j), music.get(j).withTupletRatio(3, 2));
        }
        return repaired == null ? score : score.withNotes(repaired,
                IntStream.range(0, notes.size()).boxed().toList()).withBeamSource(beams);
    }

    private static boolean matchesNotes(List<ScoreNoteEvent> row) {
        var first = row.get(0);
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            if (note.kind() != ScoreNoteEvent.Kind.PITCHED || note.staffCount() != 1 || note.staffIndex() != 0
                    || note.beamCount() != (i < 6 ? 1 : 2) || Math.abs(note.stemDirection()) != 1
                    || first.stemDirection() != note.stemDirection() || note.unbeamedDurationBeats() != 0
                    || note.tupletDivisor() != 1 || note.tupletNormalNotes() != 1 || note.augmentationDots() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0 || note.tiedFromPrevious()
                    || note.boundaryTies() != 0 || note.leadingRestBeats() != 0 || note.followingRestBeats() != 0
                    || note.crossStaffBeam() || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || note.harmonicSemitones() != 0 || note.openStringTouch() != null
                    || note.octaveShift() != first.octaveShift()) return false;
        }
        return true;
    }

    private static boolean matchesArpeggio(List<ScoreNoteEvent> row) {
        int[] steps = row.stream().mapToInt(ScoreNoteEvent::staffStep).toArray();
        return steps[0] == steps[6] && steps[1] == steps[5] && steps[2] == steps[4]
                && steps[3] == steps[10] && steps[8] == steps[12] && steps[9] == steps[11]
                && steps[0] < steps[1] && steps[1] < steps[2] && steps[2] < steps[3]
                && steps[6] < steps[7] && steps[7] < steps[8] && steps[8] < steps[9]
                && steps[9] < steps[10] && steps[13] < steps[12];
    }

    private static boolean provesRails(byte[] ink, int w, int h, MeasureRegion bar, List<ScoreNoteEvent> row) {
        float gap = (bar.bottom() - bar.top()) * h / 8.2f;
        if (gap < 10 || gap > 25 || !ClosedDottedSixteenths.hasClosingRule(ink, w, h, bar)) return false;
        var budget = new PrintedBeamShafts.Budget(400_000);
        PrintedBeamShafts.Shaft previous = null;
        float previousX = 0;
        for (int i = 0; i < row.size(); i++) {
            var note = row.get(i);
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * w;
            if (i > 0 && (x - previousX < 1.5f * gap || x - previousX > 4.5f * gap)) return false;
            var axes = PrintedBeamShafts.at(ink, w, h, x, note.pageY() * h, gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != -note.stemDirection()) return false;
            var shaft = axes.get(0);
            if (i > 0 && !(GROUP_STARTS.contains(i)
                    ? WrappedEighthTriplets.cutBetweenRecoveredGroups(ink, w, h, bar, previous, shaft, gap)
                    : WrappedEighthTriplets.matchesBeamRails(ink, w, h, previous, shaft, gap, i < 6 ? 1 : 2)))
                return false;
            previous = shaft;
            previousX = x;
        }
        return !budget.refused() && bar.right() * w - previousX >= .9f * gap;
    }

    private static ScoreBeamSource replaceGroups(ScoreBeamSource source, List<Integer> owners) {
        return replaceGroups(source, owners,
                List.of(List.of(1, 1), List.of(1, 1), List.of(3, 3, 3), List.of(3, 3, 3)), 2);
    }

    static ScoreBeamSource replaceGroups(ScoreBeamSource source, List<Integer> owners,
            List<List<Integer>> bridges, int initialTuplets) {
        var origins = owners.stream().map(source.noteOrigins()::get).toList();
        if (origins.stream().anyMatch(Optional::isEmpty)) return source;
        var anchors = origins.stream().map(Optional::orElseThrow).toList();
        if (new HashSet<>(anchors).size() != owners.size()
                || anchors.stream().anyMatch(a -> a.pageIndex() != anchors.get(0).pageIndex())
                || source.detachedColumns().stream().anyMatch(c -> c.members().stream().anyMatch(anchors::contains)))
            return source;
        var groups = new ArrayList<>(source.groups());
        int start = 0;
        for (int i = 0; i < bridges.size(); i++) {
            var masks = bridges.get(i);
            int end = start + masks.size() + 1;
            var groupAnchors = anchors.subList(start, end);
            start = end;
            var columns = groupAnchors.stream().map(a -> new ScoreBeamSource.Column(List.of(a))).toList();
            var old = groups.stream().filter(g -> g.columns().stream()
                    .anyMatch(c -> c.members().stream().anyMatch(groupAnchors::contains))).toList();
            if (old.stream().anyMatch(g -> !columns.containsAll(g.columns())
                    || !g.hooks().isEmpty() || g.tupletMark() != null)) return source;
            var first = groupAnchors.get(0);
            String id = old.isEmpty()
                    ? "printed-beam:" + first.pageIndex() + ":" + first.noteIndex() : old.get(0).id();
            int position = old.isEmpty() ? groups.size() : groups.indexOf(old.get(0));
            groups.removeAll(old);
            groups.add(position, new ScoreBeamSource.Group(id, columns,
                    masks, List.of(), i < initialTuplets ? ScoreBeamSource.TupletMark.UNNUMBERED : null));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), source.noteOrigins());
    }
}
