// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Serialize existing recognized logical silence; never expand geometry or assign AA ink. */
final class RecognizedMultiRestProjection {
    private record Run(int first, int end) {}

    static OmrScoreInterpreter.Analysis apply(
            OmrScoreInterpreter.Analysis score,
            List<MeasureRegion> measures,
            byte[] labels,
            byte[] gray,
            int w,
            int h,
            RecognizedMultiRestOrigins origins) {
        if (score == null
                || score.notes().size() > 100000
                || score.rests().size() > 100000
                || measures == null
                || origins == null
                || measures.size() > 4096
                || !origins.matches(labels, gray, w, h)) return score;
        var runs = new IdentityHashMap<MeasureRegion, Run>();
        var repeated = new IdentityHashMap<MeasureRegion, Boolean>();
        for (int first = 0; first < measures.size(); ) {
            var p = measures.get(first);
            int end = first + 1;
            while (end < measures.size() && measures.get(end) == p) end++;
            if (runs.put(p, new Run(first, end)) != null) repeated.put(p, Boolean.TRUE);
            first = end;
        }
        boolean[] notes = new boolean[measures.size()];
        int[] rests = new int[measures.size()];
        boolean[] nonFull = new boolean[measures.size()];
        for (var n : score.notes())
            if (n.measureIndex() >= 0 && n.measureIndex() < notes.length)
                notes[n.measureIndex()] = true;
        for (var r : score.rests())
            if (r.measureIndex() >= 0 && r.measureIndex() < rests.length) {
                rests[r.measureIndex()]++;
                if (!r.isFullMeasure()) nonFull[r.measureIndex()] = true;
            }
        var added = new ArrayList<ScoreRestEvent>();
        var compoundAliases = new BitSet(measures.size());
        boolean stackedOpeningKey = false;
        for (var entry : runs.entrySet()) {
            var parent = entry.getKey();
            var run = entry.getValue();
            var a = origins.admission(parent);
            if (a == null
                    || !origins.expansion(parent, run.first, run.end)
                    || repeated.containsKey(parent)
                    || run.end - run.first != a.token().value()) continue;
            var physical = origins.physical(parent);
            if (physical == null || physical.members.size() != 1) continue;
            var member = physical.members.get(0);
            var window = a.candidate().region();
            int x = Math.round((window.left() + window.right()) * .5f * w);
            float[] rows = member.physicalRows(x, w);
            if (rows == null) continue;
            boolean conflict = false, empty = true, complete = true;
            for (int i = run.first; i < run.end; i++) {
                if (notes[i] || nonFull[i] || rests[i] > 1) conflict = true;
                if (rests[i] != 0) empty = false;
                if (rests[i] != 1) complete = false;
            }
            if (a.candidate().compoundOpening()) {
                if (score.notes().stream().anyMatch(n -> n.measureIndex() >= run.first
                        && n.measureIndex() < run.end && (n.staffIndex() != 0 || n.staffCount() != 1))
                        || score.rests().stream().anyMatch(r -> r.measureIndex() >= run.first
                        && r.measureIndex() < run.end && (r.staffIndex() != 0 || r.staffCount() != 1)))
                    continue;
                compoundAliases.set(run.first, run.end);
                if (run.first == 0
                        && score.keyChanges().stream().noneMatch(key -> key.measureIndex() == 0)
                        && score.notes().stream().anyMatch(note -> note.measureIndex() == run.end
                                && note.staffIndex() == 0 && note.staffCount() == 1
                                && note.clefBottomDiatonic() == 30)) {
                    stackedOpeningKey = PrintedFlatGlyph.stackedTrebleSignature(
                            gray, w, h, parent.left() * w, rows[4], (rows[4] - rows[0]) / 4);
                }
            } else if (conflict || complete || !empty) continue;
            for (int i = run.first; i < run.end; i++)
                added.add(
                        ScoreRestEvent.fullMeasure(
                                i, .5f, rows[2] / h, (rows[4] - rows[0]) / 4 / h, 0, 1));
        }
        if (added.isEmpty()) return score;
        added.sort(Comparator.comparingInt(ScoreRestEvent::measureIndex));
        var result = new ArrayList<ScoreRestEvent>();
        int next = 0;
        for (var old : score.rests()) {
            if (compoundAliases.get(old.measureIndex())) continue;
            while (next < added.size() && added.get(next).measureIndex() < old.measureIndex())
                result.add(added.get(next++));
            result.add(old);
        }
        while (next < added.size()) result.add(added.get(next++));
        var keptNotes = compoundAliases.isEmpty() ? score.notes() : score.notes().stream()
                .filter(note -> !compoundAliases.get(note.measureIndex())).toList();
        var keys = score.keyChanges();
        if (stackedOpeningKey) {
            keys = new ArrayList<>(keys);
            keys.add(0, new ScoreKeyChange(0, -4));
        }
        return new OmrScoreInterpreter.Analysis(
                keptNotes, keys, result, score.tremoloSource());
    }
}
