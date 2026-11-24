// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** Common-time bars of four separated, staff-overlaid high ledger triplets. */
final class HighLedgerTripletBars {
    private HighLedgerTripletBars() {}

    record Capture(ScorePageInterpretation score, List<Integer> measures, List<Integer> staffOverlaid) {
        void captureSharps(ScorePageInterpretation finalScore, byte[] gray, byte[] raw, int width, int height,
                java.util.function.Consumer<SourceLocalAccidental.ProvedMark> printed) {
            var notes = finalScore.notes();
            for (int m : staffOverlaid) {
                if (m >= finalScore.measures().size() || !hasStaffOverlaidLedgerClock(notes, m)) continue;
                var bar = finalScore.measures().get(m);
                var ordered = owners(notes, m);
                if (ordered.size() != 12) continue;
                float gap = (bar.bottom() - bar.top()) * height / 8.2f;
                // The first sign can straddle the bar's left edge.
                float before = bar.left() * width - 1.45f * gap;
                for (int owner : ordered) {
                    var note = notes.get(owner);
                    float x = x(bar, note, width), y = note.pageY() * height;
                    if (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP) {
                        var a = AccidentalSlurSpines.prove(gray, width, before, x, y, gap, ScoreNoteEvent.ACCIDENTAL_SHARP);
                        var b = AccidentalSlurSpines.prove(raw, width, before, x, y, gap, ScoreNoteEvent.ACCIDENTAL_SHARP);
                        if (!a.isEmpty() && !b.isEmpty()) {
                            boolean prefix = naturalPrefixBeforeSharp(gray, width, before, x, y, gap, a)
                                    && naturalPrefixBeforeSharp(raw, width, before, x, y, gap, b);
                            printed.accept(new SourceLocalAccidental.ProvedMark(
                                    note, ScoreNoteEvent.ACCIDENTAL_SHARP, prefix));
                        }
                    }
                    before = x;
                }
            }
        }

        ScorePageInterpretation markUnnumbered(ScorePageInterpretation finalScore) {
            if (measures.isEmpty() || finalScore.beamSource().groups().isEmpty()) return finalScore;
            var beams = finalScore.beamSource();
            var groups = new ArrayList<>(beams.groups());
            for (int m : measures) {
                var owners = owners(finalScore.notes(), m);
                if (owners.size() != 12 || owners.stream().map(finalScore.notes()::get).anyMatch(n ->
                        n.beamCount() != 1 || n.tupletDivisor() != 3 || n.tupletNormalNotes() != 2)) continue;
                var columns = new ArrayList<ScoreBeamSource.Column>(12);
                for (int index : owners) beams.noteOrigins().get(index).ifPresent(a ->
                        columns.add(new ScoreBeamSource.Column(List.of(a))));
                if (columns.size() != 12) continue;
                if (staffOverlaid.contains(m)) replaceStaffOverlaidGroups(beams, groups, columns);
                else completeUnnumberedGroups(beams, groups, columns);
            }
            return groups.equals(beams.groups()) ? finalScore : finalScore.withBeamSource(
                    new ScoreBeamSource(groups, beams.detachedColumns(), beams.noteOrigins()));
        }
    }

    private static boolean naturalPrefixBeforeSharp(byte[] ink, int width, float before, float x,
            float y, float gap, List<AccidentalSlurSpines.Spine> sharp) {
        if (x - before < 3.4f * gap) return false;
        var natural = AccidentalSlurSpines.proveThinNaturalPrefix(ink, width, before, x - 1.22f * gap, y, gap);
        if (natural.isEmpty()) return false;
        float separation = sharp.get(0).left() - natural.get(1).right();
        return separation >= .28f * gap && separation <= .84f * gap;
    }

    static boolean hasStaffOverlaidLedgerClock(List<ScoreNoteEvent> notes, int measure) {
        var row = owners(notes, measure);
        return row.size() == 12 && notes.get(row.get(0)).staffStep() >= 7 && notes.get(row.get(0)).staffStep() < 10
                && row.stream().map(notes::get).filter(n -> n.staffStep() >= 10).count() >= 8
                && row.stream().map(notes::get).allMatch(n -> n.beamCount() == 1
                        && n.tupletDivisor() == 3 && n.tupletNormalNotes() == 2 && n.stemDirection() == -1);
    }

    private static void replaceStaffOverlaidGroups(ScoreBeamSource beams,
            List<ScoreBeamSource.Group> groups, List<ScoreBeamSource.Column> columns) {
        var anchors = columns.stream().flatMap(c -> c.members().stream()).collect(java.util.stream.Collectors.toSet());
        if (anchors.size() != columns.size() || beams.detachedColumns().stream()
                .anyMatch(c -> c.members().stream().anyMatch(anchors::contains))) return;
        var retained = new ArrayList<ScoreBeamSource.Group>();
        int insertAt = -1;
        for (var group : groups) {
            var members = group.columns().stream().flatMap(c -> c.members().stream()).toList();
            if (members.stream().noneMatch(anchors::contains)) {
                retained.add(group);
            } else {
                if (!anchors.containsAll(members) || !group.hooks().isEmpty()
                        || group.bridges().stream().anyMatch(bridge -> bridge != 1)
                        || group.tupletMark() == ScoreBeamSource.TupletMark.NUMBERED) return;
                if (insertAt < 0) insertAt = retained.size();
            }
        }
        if (insertAt < 0) return;
        var proved = new ArrayList<ScoreBeamSource.Group>(4);
        for (int i = 0; i < columns.size(); i += 3) {
            var first = columns.get(i).members().get(0);
            String id = "printed-beam:" + first.pageIndex() + ":" + first.noteIndex();
            if (retained.stream().anyMatch(group -> group.id().equals(id))) return;
            proved.add(new ScoreBeamSource.Group(id, columns.subList(i, i + 3), List.of(1, 1), List.of(),
                    ScoreBeamSource.TupletMark.UNNUMBERED));
        }
        retained.addAll(insertAt, proved);
        groups.clear();
        groups.addAll(retained);
    }

    private static void completeUnnumberedGroups(ScoreBeamSource source,
            List<ScoreBeamSource.Group> groups, List<ScoreBeamSource.Column> columns) {
        int absent = -1, unsupported = 0;
        int[] indexes = new int[4];
        for (int i = 0; i < indexes.length; i++) {
            var expected = columns.subList(3 * i, 3 * i + 3);
            indexes[i] = matchingGroup(groups, expected);
            if (indexes[i] < 0) {
                absent = i;
                unsupported++;
                continue;
            }
            int at = indexes[i];
            var group = groups.get(at);
            if (!group.bridges().equals(List.of(1, 1)) || !group.hooks().isEmpty()
                    || group.tupletMark() == ScoreBeamSource.TupletMark.NUMBERED) {
                unsupported++;
                continue;
            }
            if (group.tupletMark() == null)
                groups.set(at, new ScoreBeamSource.Group(group.id(), expected,
                        group.bridges(), group.hooks(), ScoreBeamSource.TupletMark.UNNUMBERED));
        }
        // The three confirmed companions and the dual-plane bar proof identify one omitted group.
        if (unsupported != 1 || absent < 0) return;
        var expected = columns.subList(3 * absent, 3 * absent + 3);
        for (var col : expected) {
            var anchor = col.members().get(0);
            if (source.noteOrigins().stream().filter(o -> o.isPresent() && o.get().equals(anchor)).count() != 1
                    || source.detachedColumns().stream().anyMatch(c -> c.members().contains(anchor))
                    || groups.stream().flatMap(g -> g.columns().stream())
                            .anyMatch(c -> c.members().contains(anchor))) return;
        }
        var first = expected.get(0).members().get(0);
        String id = "printed-beam:" + first.pageIndex() + ":" + first.noteIndex();
        if (groups.stream().anyMatch(g -> g.id().equals(id))) return;
        int at = absent == 0 ? indexes[1] : indexes[absent - 1] + 1;
        groups.add(at, new ScoreBeamSource.Group(id, expected, List.of(1, 1), List.of(),
                ScoreBeamSource.TupletMark.UNNUMBERED));
    }

    private static int matchingGroup(List<ScoreBeamSource.Group> groups, List<ScoreBeamSource.Column> expected) {
        for (int i = 0; i < groups.size(); i++)
            if (groups.get(i).columns().equals(expected)) return i;
        return -1;
    }

    static Capture capture(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] gray, byte[] raw, int width, int height) {
        return capture(score, inherited, labels, gray, raw, width, height, false);
    }

    static Capture captureStaffOverlaid(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] gray, byte[] raw, int width, int height) {
        return capture(score, inherited, labels, gray, raw, width, height, true);
    }

    private static Capture capture(ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] labels, byte[] gray, byte[] raw, int width, int height, boolean staffOverlaidOnly) {
        if (gray == null || raw == null || labels == null || gray.length != (long) width * height
                || raw.length != gray.length || labels.length != gray.length
                || score.notes().isEmpty()) return new Capture(score, List.of(), List.of());
        var measures = score.measures();
        var edits = new ArrayList<>(score.notes());
        var origins = new ArrayList<>(IntStream.range(0, edits.size()).boxed().toList());
        var changed = new ArrayList<Integer>();
        var staffOverlaid = new ArrayList<Integer>();
        ScoreMeterChange meter = inherited == null ? new ScoreMeterChange(0, 4, 4) : inherited;
        int next = 0;
        for (int m = 0; m < measures.size(); m++) {
            while (next < score.meterChanges().size() && score.meterChanges().get(next).measureIndex() <= m)
                meter = score.meterChanges().get(next++);
            if (meter.numerator() != 4 || meter.denominator() != 4 || measures.get(m).cadenceQuarterBeats() != 0)
                continue;
            var owners = owners(edits, m);
            if (owners.size() < 11 || owners.size() > 13) continue;
            int measure = m;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var bar = measures.get(m);
            if (owners.size() == 12 && edits.get(owners.get(0)).staffStep() < 10
                    && ordinaryShape(edits, owners, 7)
                    && owners.stream().map(edits::get).allMatch(n -> n.beamCount() == 1)
                    && owners.stream().map(edits::get).filter(n -> n.staffStep() >= 10).count() >= 8
                    && WrappedEighthTriplets.provesStaffOverlaidLedgerTriplets(gray, width, height, bar, edits, owners)
                    && WrappedEighthTriplets.provesStaffOverlaidLedgerTriplets(raw, width, height, bar, edits, owners)) {
                for (int i : owners) edits.set(i, edits.get(i).withTupletRatio(3, 2));
                changed.add(m);
                staffOverlaid.add(m);
                continue;
            }
            if (staffOverlaidOnly) continue;
            if (owners.size() == 13) {
                int alias = ledgerBeamTip(edits, owners);
                if (alias < 0) continue;
                var retained = owners.stream().filter(i -> i != alias).toList();
                if (!ordinaryShape(edits, retained)) continue;
                var falseHead = edits.get(alias);
                if (!LedgerBeamTerminalAlias.proves(gray, width, height, bar, edits, retained, falseHead)
                        || !LedgerBeamTerminalAlias.proves(raw, width, height, bar, edits, retained, falseHead))
                    continue;
                for (int i : retained)
                    edits.set(i, ClosingTripletRhythm.singleBeam(edits.get(i)).withTupletRatio(3, 2));
                edits.remove(alias);
                origins.remove(alias);
                changed.add(m);
                continue;
            }
            if (!ordinaryShape(edits, owners)) continue;
            int added = -1;
            if (owners.size() == 11) {
                var note = missingFourthGroupHead(edits, owners, bar, labels, gray, raw, width, height);
                if (note == null) continue;
                added = owners.get(9);
                edits.add(added, note);
                owners = owners(edits, m);
            }
            if (!WrappedEighthTriplets.provesClosedUpperLedgerTriplets(gray, width, height, measures, m, edits, owners)
                    || !WrappedEighthTriplets.provesClosedUpperLedgerTriplets(raw, width, height, measures, m, edits, owners)) {
                if (added >= 0) edits.remove(added);
                continue;
            }
            if (added >= 0) origins.add(added, -1);
            for (int i : owners) edits.set(i, ClosingTripletRhythm.singleBeam(edits.get(i)).withTupletRatio(3, 2));
            changed.add(m);
        }
        return changed.isEmpty() ? new Capture(score, List.of(), List.of())
                : new Capture(score.withNotes(edits, origins), List.copyOf(changed), List.copyOf(staffOverlaid));
    }

    private static int ledgerBeamTip(List<ScoreNoteEvent> notes, List<Integer> owners) {
        int candidate = -1;
        for (int owner : owners) {
            var n = notes.get(owner);
            if (n.stemDirection() != 1) continue;
            if (candidate >= 0 || n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffStep() < 0 || n.staffStep() > 8
                    || n.staffIndex() != 0 || n.staffCount() != 1 || n.crossStaffBeam()
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.beamCount() != 0
                    || n.unbeamedDurationBeats() != ScoreNoteEvent.DURATION_QUARTER
                    || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || n.augmentationDots() != 0 || n.tupletDivisor() != 1
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.articulations() != 0
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null || n.octaveShift() != 0)
                return -1;
            candidate = owner;
        }
        return candidate;
    }

    private static List<Integer> owners(List<ScoreNoteEvent> notes, int measure) {
        return IntStream.range(0, notes.size()).filter(i -> notes.get(i).measureIndex() == measure).boxed()
                .sorted(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure())).toList();
    }

    private static boolean ordinaryShape(List<ScoreNoteEvent> notes, List<Integer> owners) {
        return ordinaryShape(notes, owners, 10);
    }

    private static boolean ordinaryShape(List<ScoreNoteEvent> notes, List<Integer> owners, int minimumStep) {
        int extra = 0, clef = notes.get(owners.get(0)).clefBottomDiatonic();
        for (int i : owners) {
            var n = notes.get(i);
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                    || n.crossStaffBeam() || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.stemDirection() != -1 || n.staffStep() < minimumStep || n.clefBottomDiatonic() != clef
                    || n.beamCount() < 1 || n.beamCount() > 2 || n.augmentationDots() != 0
                    || n.tupletDivisor() != 1 || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.unbeamedDurationBeats() != 0 || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.harmonicSemitones() != 0 || n.openStringTouch() != null || n.octaveShift() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
            if (n.beamCount() == 2 && ++extra > 1) return false;
        }
        return clef != ScoreNoteEvent.CLEF_UNKNOWN;
    }

    private static ScoreNoteEvent missingFourthGroupHead(List<ScoreNoteEvent> notes, List<Integer> owners,
            MeasureRegion bar, byte[] labels, byte[] gray, byte[] raw, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return null;
        var matchingPeak = notes.get(owners.get(3));
        var before = notes.get(owners.get(8));
        var after = notes.get(owners.get(9));
        var end = notes.get(owners.get(10));
        if (matchingPeak.staffStep() < before.staffStep() + 2 || matchingPeak.staffStep() < after.staffStep() + 2
                || after.staffStep() < end.staffStep() + 1
                || matchingPeak.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return null;
        float xa = x(bar, before, width), xb = x(bar, after, width), xe = x(bar, end, width);
        if (xb - xa < gap * 4.4f || xb - xa > gap * 5.9f
                || Math.abs((xb - xa) * .5f - (xe - xb)) > gap * .42f) return null;
        float centre = (xa + xb) * .5f, pitchY = matchingPeak.pageY() * height;
        int left = Math.round(centre - gap * 1.18f), right = Math.round(centre + gap * 1.18f);
        int top = Math.round(pitchY - gap * .95f), bottom = Math.round(pitchY + gap * .85f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        long totalX = 0, totalY = 0;
        int lowX = right, highX = left, lowY = bottom, highY = top, area = 0, dark = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int pixel = y * width + x;
                if (labels[pixel] != OmrMeasurePostProcessor.NOTEHEAD) continue;
                area++;
                if ((gray[pixel] & 255) < 155 && (raw[pixel] & 255) < 155) dark++;
                totalX += x;
                totalY += y;
                lowX = Math.min(lowX, x);
                highX = Math.max(highX, x);
                lowY = Math.min(lowY, y);
                highY = Math.max(highY, y);
            }
        }
        if (lowX <= left || highX >= right || lowY <= top || highY >= bottom) return null;
        int w = highX - lowX + 1, h = highY - lowY + 1;
        if (w < gap * 1.11f || w > gap * 1.6f || h < gap * 1.02f || h > gap * 1.50f
                || area < w * h * .65f || dark < area * .92f) return null;
        float x = (float) totalX / area, y = (float) totalY / area;
        int step = matchingPeak.staffStep();
        if (Math.abs(x - centre) > gap * .26f || Math.abs(y - pitchY) > gap * .30f
                || (step & 1) == 0
                || !ledgerSpace(gray, width, height, x, y, gap, step)
                || !ledgerSpace(raw, width, height, x, y, gap, step)
                || accidentalBeforeHead(labels, width, height, x, y, gap)) return null;
        return new ScoreNoteEvent(matchingPeak.measureIndex(), (x / width - bar.left()) / (bar.right() - bar.left()),
                matchingPeak.staffStep(), 0, 1, y / height, false, 0, 1,
                ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0, matchingPeak.clefBottomDiatonic(),
                false, 0, false, 0, 0, 1, -1, ScoreNoteEvent.Kind.PITCHED, ScoreNoteEvent.VisualSize.FULL);
    }

    private static boolean ledgerSpace(byte[] gray, int width, int height,
            float x, float y, float nominal, int step) {
        var staff = PrintedDirectionStaff.printedRules(
                gray, width, height, x, y + step * nominal * .5f, nominal);
        if (staff == null) return false;
        float gap = staff[1], ruled = staff[0] - (step - 1) * gap * .5f;
        int left = Math.round(x - gap * .85f), right = Math.round(x + gap * .85f);
        int first = Math.round(Math.max(y + gap * .43f, ruled - gap * .28f));
        int last = Math.round(Math.min(y + gap * .84f, ruled + gap * .28f));
        if (left < 0 || right >= width || first < 0 || last >= height) return false;
        for (int row = first; row <= last; row++) {
            int ink = 0;
            for (int col = left; col <= right; col++)
                if ((gray[row * width + col] & 255) < 155) ink++;
            if (ink >= (right - left + 1) * .94f) return true;
        }
        return false;
    }

    private static boolean accidentalBeforeHead(byte[] labels, int width, int height,
            float x, float y, float gap) {
        int left = Math.round(x - 2.3f * gap), right = Math.round(x - .92f * gap);
        int top = Math.round(y - 1.20f * gap), bottom = Math.round(y + .58f * gap);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return true;
        int ink = 0;
        for (int row = top; row <= bottom; row++) {
            for (int col = left; col <= right; col++) {
                if (labels[row * width + col] == OmrMeasurePostProcessor.CLEF_OR_KEY
                        && ++ink > gap * .85f) return true;
            }
        }
        return false;
    }

    private static float x(MeasureRegion bar, ScoreNoteEvent note, int width) {
        return (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
    }
}
