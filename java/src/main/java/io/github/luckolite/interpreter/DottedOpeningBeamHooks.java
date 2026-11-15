// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retains a physical backward hook on the ending of an eighth–dotted-sixteenth–32nd beam. */
final class DottedOpeningBeamHooks {
    private DottedOpeningBeamHooks() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, byte[] source, int width, int height) {
        if (gray == null
                || source == null
                || width < 1
                || height < 1
                || (long) width * height != gray.length
                || gray.length != source.length) return score;
        var beams = score.beamSource();
        if (beams.groups().isEmpty()) return score;
        var byMeasure = new HashMap<Integer, List<Head>>();
        var byOrigin = new HashMap<ScoreBeamSource.Anchor, Head>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            int measure = note.measureIndex();
            if (measure < 0 || measure >= score.measures().size()) continue;
            var bounds = score.measures().get(measure);
            float x =
                    (bounds.left() + note.positionInMeasure() * (bounds.right() - bounds.left()))
                            * width;
            var head = new Head(i, note, x, note.pageY() * height);
            byMeasure.computeIfAbsent(measure, ignored -> new ArrayList<>()).add(head);
            var origin = beams.noteOrigins().get(i);
            if (origin.isPresent())
                byOrigin.merge(origin.get(), head, (a, b) -> new Head(-1, a.note, a.x, a.y));
        }
        for (var row : byMeasure.values()) row.sort(Comparator.comparingDouble(Head::x));

        Set<ScoreBeamSource.Anchor> claimed = new HashSet<>();
        for (var group : beams.groups())
            for (var column : group.columns()) claimed.addAll(column.members());
        for (var column : beams.detachedColumns()) claimed.addAll(column.members());
        var revised = new ArrayList<ScoreBeamSource.Group>();
        boolean changed = false;
        for (var group : beams.groups()) {
            var next =
                    complete(
                            group,
                            beams,
                            byOrigin,
                            byMeasure,
                            claimed,
                            score.measures(),
                            gray,
                            source,
                            width,
                            height);
            changed |= next != group;
            revised.add(next);
        }
        return changed
                ? score.withBeamSource(
                        new ScoreBeamSource(revised, beams.detachedColumns(), beams.noteOrigins()))
                : score;
    }

    private static ScoreBeamSource.Group complete(
            ScoreBeamSource.Group group,
            ScoreBeamSource beamSource,
            HashMap<ScoreBeamSource.Anchor, Head> byOrigin,
            HashMap<Integer, List<Head>> byMeasure,
            Set<ScoreBeamSource.Anchor> claimed,
            List<MeasureRegion> measures,
            byte[] gray,
            byte[] source,
            int width,
            int height) {
        int columns = group.columns().size();
        if (columns < 2
                || columns > 3
                || group.bridges().get(0) != 1
                || !group.hooks().isEmpty()
                || columns == 3 && group.bridges().get(1) != 3) return group;
        var first = monophonic(group.columns().get(0), byOrigin);
        var dotted = monophonic(group.columns().get(1), byOrigin);
        if (!pitchedUp(first, 1, 0)
                || !pitchedUp(dotted, 2, 1)
                || first.note.measureIndex() != dotted.note.measureIndex()) return group;
        int measure = first.note.measureIndex();
        var row = byMeasure.get(measure);
        int offset = row.indexOf(first);
        if (offset < 0 || offset + 2 >= row.size() || row.get(offset + 1) != dotted) return group;
        var shortNote = row.get(offset + 2);
        if (!pitchedUp(shortNote, 3, 0)
                || first.note.staffIndex() != dotted.note.staffIndex()
                || first.note.staffIndex() != shortNote.note.staffIndex()
                || first.note.clefBottomDiatonic() != dotted.note.clefBottomDiatonic()
                || first.note.clefBottomDiatonic() != shortNote.note.clefBottomDiatonic())
            return group;
        var anchor = beamSource.noteOrigins().get(shortNote.index).orElse(null);
        if (anchor == null
                || byOrigin.get(anchor) != shortNote
                || anchor.pageIndex() != group.columns().get(0).members().get(0).pageIndex()
                || (columns == 3
                        ? monophonic(group.columns().get(2), byOrigin) != shortNote
                        : claimed.contains(anchor))) return group;

        var bounds = measures.get(measure);
        float gap = (bounds.bottom() - bounds.top()) * height / 8.2f;
        if (gap < 10
                || gap > 27
                || !distinctSpacing(first.x, dotted.x, gap)
                || !distinctSpacing(dotted.x, shortNote.x, gap)
                || offset > 0 && first.x - row.get(offset - 1).x < gap * 1.05f
                || offset + 3 < row.size() && row.get(offset + 3).x - shortNote.x < gap * 1.05f
                || !ownsHook(gray, width, height, shortNote, dotted, gap)
                || source != gray && !ownsHook(source, width, height, shortNote, dotted, gap))
            return group;

        List<ScoreBeamSource.Column> members = group.columns();
        List<Integer> bridges = group.bridges();
        if (columns == 2) {
            claimed.add(anchor);
            members =
                    List.of(
                            members.get(0),
                            members.get(1),
                            new ScoreBeamSource.Column(List.of(anchor)));
            bridges = List.of(1, 3);
        }
        return new ScoreBeamSource.Group(
                group.id(),
                members,
                bridges,
                List.of(new ScoreBeamSource.Hook(2, 3, ScoreBeamSource.Direction.BACKWARD)));
    }

    private static Head monophonic(
            ScoreBeamSource.Column column, HashMap<ScoreBeamSource.Anchor, Head> handles) {
        return column.members().size() == 1 ? handles.get(column.members().get(0)) : null;
    }

    private static boolean pitchedUp(Head head, int beams, int dots) {
        if (head == null || head.index < 0) return false;
        var n = head.note;
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffCount() == 1
                && n.stemDirection() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.unbeamedDurationBeats() == 0
                && n.beamCount() == beams
                && n.augmentationDots() == dots
                && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && !n.crossStaffBeam()
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean distinctSpacing(float a, float b, float gap) {
        return b - a >= gap * 1.65f && b - a <= gap * 4.1f;
    }

    private static boolean ownsHook(byte[] gray, int w, int h, Head owner, Head dotted, float gap) {
        return OwnedTertiaryBeamlets.provesMonophonicBackwardHook(
                gray,
                w,
                h,
                new OwnedTertiaryBeamlets.Head(owner.index, owner.note, owner.x, owner.y, gap),
                new OwnedTertiaryBeamlets.Head(dotted.index, dotted.note, dotted.x, dotted.y, gap),
                gap);
    }
}
