// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Reads repeated closed systems of two three-beat dyad runs and an isolated quarter. */
final class ClosedLateTripleDyads {
    private ClosedLateTripleDyads() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}
    private record Stem(int x, int tip, int direction) {}
    private record Attack(List<Head> heads, Stem stem) {}
    private record Row(List<Attack> attacks, ScoreRestEvent naturalAlias) {}
    private record Ink(byte[] gray, int width, int height) {
        boolean dark(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height && (gray[y * width + x] & 255) < 150;
        }
        boolean core(int x, int y) {
            return dark(x, y) && (dark(x, y - 1) || dark(x, y + 1));
        }
    }

    static ScorePageInterpretation normalize(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, int width, int height) {
        var rows = qualify(score, initial, gray, width, height);
        if (rows.isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        var beamOwners = new HashSet<Integer>();
        int aliasIndex = -1;
        for (var row : rows) {
            if (row.naturalAlias() != null) aliasIndex = score.rests().indexOf(row.naturalAlias());
            for (var attack : row.attacks()) {
                for (var head : attack.heads()) {
                    var n = head.note();
                    if (n.beamCount() == 1) beamOwners.add(head.index());
                    if (n.beamCount() == 1 || n.followingRestBeats() > 0)
                        notes.set(head.index(), plainSixteenth(n));
                }
            }
        }
        var measures = new ArrayList<>(score.measures());
        int last = measures.size() - 1;
        var finalBar = measures.get(last);
        if (notes.equals(score.notes()) && aliasIndex < 0 && finalBar.cadenceQuarterBeats() == 1) return score;
        int aliasBar = aliasIndex < 0 ? -1 : score.rests().get(aliasIndex).measureIndex();
        var context = revisedContext(score.clockContextSource(), beamOwners, aliasIndex, aliasBar);
        if (context == null) return score;
        var rests = new ArrayList<>(score.rests());
        if (aliasIndex >= 0) rests.remove(aliasIndex);
        measures.set(last, new MeasureRegion(finalBar.left(), finalBar.right(), finalBar.top(), finalBar.bottom(), 1));
        return new ScorePageInterpretation(measures, notes, score.firstMeasureNumber(),
                score.keyChanges(), score.tempoChanges(), score.meterChanges(), rests,
                score.techniqueChanges(), score.dynamicChanges(), score.playbackDirections(), score.expressiveEvents(),
                score.slurSource(), score.openingPickup(), score.tremoloSource(), score.multiRestGroups(),
                score.beamSource(), context);
    }

    private static ScoreClockContextSource revisedContext(ScoreClockContextSource source,
            Set<Integer> beamOwners, int aliasIndex, int aliasBar) {
        if (source.printedValues().stream().anyMatch(v -> beamOwners.contains(v.noteIndex())
                && v.status() == ScoreClockContextSource.Status.SOURCE_PREMISE && v.beams() != 2)) return null;
        if (aliasIndex >= 0 && (source.glyphLedgers().stream().anyMatch(
                    g -> g.restMembers() != 0 && (g.measure() < 0 || g.measure() == aliasBar))
                || source.restValues().stream().anyMatch(r -> r.restIndex() == aliasIndex
                    && r.status() == ScoreClockContextSource.Status.SOURCE_PREMISE)
                || source.voiceGroups().stream().anyMatch(g -> g.restIndices().contains(aliasIndex)))) return null;
        var values = source.printedValues().stream().map(v -> !beamOwners.contains(v.noteIndex()) ? v
                : new ScoreClockContextSource.PrintedValue(v.noteIndex(), v.measure(), v.staff(), v.staffs(),
                        v.kind(), v.baseBeats(), v.dots(), 2, v.route(), v.originalExit(), v.admissionFlags(),
                        v.selectedMembers(), v.parentMembers(), v.parentLeaves(), v.residualMembers(),
                        v.selectedSha256(), v.parentSha256(), v.model(), v.status())).toList();
        var rests = source.restValues();
        var voices = source.voiceGroups();
        int count = source.restCount();
        if (aliasIndex >= 0) {
            count--;
            rests = rests.stream().filter(r -> r.restIndex() != aliasIndex)
                    .map(r -> r.restIndex() < aliasIndex ? r
                            : new ScoreClockContextSource.RestValue(r.restIndex() - 1,
                                    r.measure(), r.staff(), r.staffs(), r.kind(), r.baseBeats(), r.dots(),
                                    r.membersSha256(), r.producerRoute(), r.status())).toList();
            voices = voices.stream().map(g -> g.restIndices().stream().noneMatch(r -> r > aliasIndex) ? g
                    : new ScoreClockContextSource.VoiceGroup(g.measure(), g.staff(), g.staffs(),
                            g.voiceId(), g.ordinal(), g.kind(), g.noteIndices(),
                            g.restIndices().stream().map(r -> r < aliasIndex ? r : r - 1).toList(),
                            g.membersSha256(), g.sourceRoute(), g.status())).toList();
        }
        return new ScoreClockContextSource(source.origin(), source.noteCount(), source.measureCount(), count,
                source.partWords(), source.numbers(), source.meters(), values, source.glyphLedgers(), rests,
                voices, source.syncAnchors(), source.missing());
    }

    static float[] place(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, int width, int height, float[] beats, boolean[] cadences) {
        int first = beats.length - 6;
        if (first < 1 || first + 6 != score.measures().size()
                || beats[first - 1] != 2 || beats[first] != 4 || beats[first + 1] != 4
                || beats[first + 2] != 1 || beats[first + 3] != 4 || beats[first + 4] != 4
                || beats[first + 5] != 4 && beats[first + 5] != 1) return new float[0];
        var rows = qualify(score, initial, gray, width, height);
        if (rows.isEmpty()) return new float[0];
        var onsets = new float[score.notes().size()];
        Arrays.fill(onsets, Float.NaN);
        for (int row = 0; row < rows.size(); row++) {
            int start = first + 3 * row;
            beats[start] = 3;
            beats[start + 1] = 3;
            beats[start + 2] = 1;
            cadences[start + 2] = true;
            var attacks = rows.get(row).attacks();
            for (int i = 0; i < attacks.size(); i++) {
                float onset = (i % 12) * .25f;
                for (var head : attacks.get(i).heads()) onsets[head.index()] = onset;
            }
        }
        return onsets;
    }

    private static List<Row> qualify(ScorePageInterpretation score, ScoreMeterChange initial,
            byte[] gray, int w, int h) {
        if (gray == null || gray.length != (long) w * h || w < 640 || h < 640) return List.of();
        var bars = score.measures();
        int first = bars.size() - 6;
        if (first < 1 || bars.get(first - 1).cadenceQuarterBeats() != 2) return List.of();
        var active = initial;
        for (var m : score.meterChanges()) {
            if (m.measureIndex() >= first - 1) return List.of();
            active = m;
        }
        if (active == null || active.numerator() != 4 || active.denominator() != 4) return List.of();

        float g = gap(bars.get(first), h);
        float above = (bars.get(first).top() - bars.get(first - 1).bottom()) * h;
        float between = (bars.get(first + 3).top() - bars.get(first + 2).bottom()) * h;
        if (g < 9 || g > 27 || above < g * 2 || above > g * 11
                || between < g * 2 || between > g * 11) return List.of();
        var ink = new Ink(gray, w, h);
        var top = row(score, ink, first);
        if (top == null) return List.of();
        var bottom = row(score, ink, first + 3);
        return bottom == null || top.naturalAlias() != null && bottom.naturalAlias() != null
                ? List.of() : List.of(top, bottom);
    }

    private static Row row(ScorePageInterpretation score, Ink ink, int first) {
        var a = score.measures().get(first);
        var b = score.measures().get(first + 1);
        var c = score.measures().get(first + 2);
        int h = ink.height(), w = ink.width();
        float g = gap(a, h);
        if (!sameRow(a, b, h, g) || !sameRow(b, c, h, g)
                || a.cadenceQuarterBeats() != 0 || b.cadenceQuarterBeats() != 0
                || c.cadenceQuarterBeats() > 1 || a.left() < .07f || a.left() > .19f
                || c.right() < .87f || c.right() > .99f
                || !span(a, w, g, 41, 55) || !span(b, w, g, 41, 53)
                || !span(c, w, g, 8, 11.8f)) return null;
        float ab = (a.right() + b.left()) * w * .5f;
        float bc = (b.right() + c.left()) * w * .5f;
        var firstRule = staff(ink, a, ab);
        var secondRule = staff(ink, b, bc);
        var endStaff = staff(ink, c, c.right() * w - g * 2);
        if (firstRule == null || secondRule == null || endStaff == null
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(ink.gray(), w, h, firstRule, ab)
                || !ClosedSixteenthPairBeforeHalf.ownedSeparator(ink.gray(), w, h, secondRule, bc)) return null;
        int edge = PrintedCadenceDurations.boundaryColumn(ink.gray(), w, endStaff,
                c.right() * w + .30f * g, false, false);
        if (edge < 0) edge = PrintedCadenceDurations.boundaryColumn(
                ink.gray(), w, endStaff, c.right() * w, false, false);
        if (edge < 0 || !quarter(score, ink, c, first + 2, endStaff, bc, edge)) return null;

        var rests = score.rests().stream().filter(r -> r.measureIndex() >= first
                && r.measureIndex() <= first + 2).toList();
        if (rests.size() > 1
                || !rests.isEmpty() && (rests.get(0).isFullMeasure()
                || rests.get(0).durationBeats() != .125f
                || rests.get(0).measureIndex() == first + 2)) return null;
        var alias = rests.isEmpty() ? null : rests.get(0);
        var left = sixteenths(score, ink, a, first, firstRule, alias);
        var right = sixteenths(score, ink, b, first + 1, secondRule, alias);
        if (left.isEmpty() || right.isEmpty()) return null;
        var attacks = new ArrayList<>(left);
        attacks.addAll(right);
        return new Row(attacks, alias);
    }

    private static List<Attack> sixteenths(ScorePageInterpretation score, Ink ink,
            MeasureRegion bar, int index, PlayingTechniqueDetector.Staff frame, ScoreRestEvent alias) {
        float g = frame.gap();
        var grouped = attacks(score, ink, bar, index, g);
        if (grouped.size() != 12) return List.of();
        int single = 0, shortBeam = 0;
        int aliasBefore = -1;
        if (alias != null && alias.measureIndex() == index) {
            aliasBefore = naturalAlias(ink, grouped, bar, frame, alias);
            if (aliasBefore < 0) return List.of();
        }
        var result = new ArrayList<Attack>();
        for (int i = 0; i < grouped.size(); i++) {
            var heads = grouped.get(i);
            var n = heads.get(0).note();
            if (heads.size() > 2 || heads.size() == 1 && ++single > 1) return List.of();
            if (n.beamCount() != 2 && (n.beamCount() != 1 || ++shortBeam > 1)) return List.of();
            for (var h : heads) {
                var v = h.note();
                if (!ordinary(v) || v.unbeamedDurationBeats() != 0 || v.beamCount() != n.beamCount()
                        || n.stemDirection() != v.stemDirection()
                        || v.followingRestBeats() != (i == aliasBefore ? .125f : 0)) return List.of();
            }
            if (n.stemDirection() == 0) return List.of();
            var stem = commonShaft(ink, heads, g);
            if (stem == null) return List.of();
            if (heads.size() == 2) {
                if (Math.abs(heads.get(1).note().staffStep() - n.staffStep()) != 5) return List.of();
            } else if (!mateFills(ink, heads.get(0), stem, g)) return List.of();
            if (i > 0) {
                float spacing = heads.get(0).x() - grouped.get(i - 1).get(0).x();
                if (spacing < g * 2.65f || spacing > g * 5.15f) return List.of();
            }
            result.add(new Attack(heads, stem));
        }
        for (int i = 0; i < 12; i += 4) {
            var stems = result.subList(i, i + 4).stream().map(Attack::stem).toList();
            if (!twoRails(ink, stems, g)) return List.of();
        }
        return result;
    }

    private static boolean quarter(ScorePageInterpretation score, Ink ink, MeasureRegion bar, int index,
            PlayingTechniqueDetector.Staff staff, float left, int right) {
        var attacks = attacks(score, ink, bar, index, staff.gap());
        if (attacks.size() != 1 || attacks.get(0).size() != 2) return false;
        var heads = attacks.get(0);
        var a = heads.get(0);
        var b = heads.get(1);
        if (a.note().stemDirection() == 0 || a.note().stemDirection() != b.note().stemDirection()
                || Math.abs(a.note().staffStep() - b.note().staffStep()) != 5
                || a.x() - left < staff.gap() * 2.4f || right - b.x() < staff.gap() * 4.4f) return false;
        return heads.stream().allMatch(x -> ordinary(x.note()) && x.note().unbeamedDurationBeats() == 1
                && x.note().beamCount() == 0 && x.note().followingRestBeats() == 0
                && !x.note().tiedFromPrevious())
                && commonShaft(ink, heads, staff.gap()) != null
                && PrintedCadenceDurations.emptyOutsideChord(
                        ink.gray(), ink.width(), staff, left, right, a.x(), b.x(), false);
    }

    private static List<List<Head>> attacks(ScorePageInterpretation score, Ink ink,
            MeasureRegion bar, int index, float gap) {
        float span = (bar.right() - bar.left()) * ink.width();
        var owners = new ArrayList<Head>();
        for (int i = 0; i < score.notes().size(); i++) {
            var n = score.notes().get(i);
            if (n.measureIndex() == index) owners.add(new Head(i, n,
                    bar.left() * ink.width() + n.positionInMeasure() * span, n.pageY() * ink.height()));
        }
        owners.sort(Comparator.comparingDouble(Head::x));
        var attacks = new ArrayList<List<Head>>();
        for (var owner : owners) {
            if (attacks.isEmpty() || owner.x() - attacks.get(attacks.size() - 1).get(0).x() > .25f * gap)
                attacks.add(new ArrayList<>());
            attacks.get(attacks.size() - 1).add(owner);
        }
        return attacks;
    }

    private static Stem commonShaft(Ink ink, List<Head> chord, float gap) {
        var budget = new PrintedBeamShafts.Budget(50_000);
        int direction = -chord.get(0).note().stemDirection();
        int left = 0, right = ink.width(), tip = direction > 0 ? 0 : ink.height();
        int previous = -1;
        for (var h : chord) {
            var axes = PrintedBeamShafts.at(ink.gray(), ink.width(), ink.height(), h.x(), h.y(), gap, budget);
            if (axes.size() != 1 || axes.get(0).direction() != direction) return null;
            var axis = axes.get(0);
            if (previous >= 0 && Math.abs(previous - axis.endY()) > gap * .28f) return null;
            previous = axis.endY();
            left = Math.max(left, axis.left());
            right = Math.min(right, axis.right());
            tip = direction > 0 ? Math.max(tip, axis.endY()) : Math.min(tip, axis.endY());
        }
        return left > right ? null : new Stem((left + right) / 2, tip, direction);
    }

    private static boolean mateFills(Ink ink, Head head, Stem owner, float gap) {
        int found = 0;
        for (int side : new int[] {-1, 1}) {
            boolean matches = false;
            for (int dy : new int[] {0, -2, 2}) {
                float y = head.y() + side * gap * 2.5f + dy;
                if (!FilledSparseBeamHead.completeInterior(ink.gray(), ink.width(), ink.height(),
                        head.x(), y, gap)) continue;
                var stems = PrintedBeamShafts.at(ink.gray(), ink.width(), ink.height(), head.x(), y, gap,
                        new PrintedBeamShafts.Budget(50_000));
                if (stems.size() == 1) {
                    var candidate = stems.get(0);
                    matches |= candidate.direction() == owner.direction()
                            && candidate.left() <= owner.x() && candidate.right() >= owner.x()
                            && Math.abs(candidate.endY() - owner.tip()) < gap * .22f;
                }
            }
            if (matches) found++;
        }
        return found == 1;
    }

    private static boolean twoRails(Ink ink, List<Stem> stems, float gap) {
        var a = stems.get(0);
        var b = stems.get(stems.size() - 1);
        int direction = a.direction();
        if (stems.stream().anyMatch(s -> s.direction() != direction)) return false;
        int radius = Math.round(gap * .20f);
        int reach = Math.round(gap * .54f);
        for (int first = -radius; first <= reach; first++) {
            for (int last = -radius; last <= reach; last++) {
                float base = a.tip() - direction * first;
                float slope = (b.tip() - direction * last - base) / (b.x() - a.x());
                if (Math.abs(slope) > .28f || !continuous(ink, stems, base, slope, radius, .94f)) continue;
                for (int separation = Math.round(gap * .36f); separation <= gap * .94f; separation++) {
                    if (!continuous(ink, stems, base - direction * separation, slope, radius, .94f)
                            || continuous(ink, stems, base - 2 * direction * separation, slope, radius, .72f))
                        continue;
                    int blank = 0;
                    for (int x = a.x(); x <= b.x(); x++) {
                        int y = Math.round(base + slope * (x - a.x()) - direction * (separation / 2));
                        if (!ink.dark(x, y)) blank++;
                    }
                    if (blank >= (b.x() - a.x() + 1) * .55f) return true;
                }
            }
        }
        return false;
    }

    private static boolean continuous(Ink ink, List<Stem> stems, float base,
            float slope, int padding, float fraction) {
        int origin = stems.get(0).x();
        for (int i = 1; i < stems.size(); i++) {
            int start = stems.get(i - 1).x() + padding;
            int end = stems.get(i).x() - padding;
            int count = 0;
            for (int x = start; x <= end; x++)
                if (ink.core(x, Math.round(base + slope * (x - origin)))) count++;
            if (count < (end - start + 1) * fraction) return false;
        }
        return true;
    }

    private static int naturalAlias(Ink ink, List<List<Head>> attacks, MeasureRegion bar,
            PlayingTechniqueDetector.Staff frame, ScoreRestEvent rest) {
        float g = frame.gap();
        float x = (bar.left() + rest.positionInMeasure() * (bar.right() - bar.left())) * ink.width();
        if (rest.staffCount() != 1 || rest.staffIndex() != 0
                || rest.pageHeight() * ink.height() < 3.4f * g
                || rest.pageHeight() * ink.height() > 4.1f * g) return -1;
        for (int i = 1; i < attacks.size(); i++) {
            var next = attacks.get(i).stream().sorted(Comparator.comparingDouble(Head::y)).toList();
            var previous = attacks.get(i - 1);
            if (next.size() != 2 || previous.size() != 2) continue;
            float after = next.get(0).x() - x;
            float before = x - previous.get(previous.size() - 1).x();
            if (after < 1.5f * g || after > 2.1f * g || before < 1.4f * g || before > 2.15f * g
                    || Math.abs(rest.pageY() * ink.height() - (next.get(next.size() - 1).y() - g * .5f)) > g * .20f
                    || i % 4 == 0) continue;
            if (OmrScoreInterpreter.provesNaturalMeterInk(ink.gray(), ink.width(), ink.height(),
                    Math.round(x - g * .64f), Math.round(x + g * .48f), next.get(0).y() - g * .50f,
                    g, Math.round(frame.top()))
                    && lowerNatural(ink, x, next.get(next.size() - 1).y(), frame)) return i - 1;
        }
        return -1;
    }

    private static boolean lowerNatural(Ink ink, float x, float y, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        for (int l = Math.round(x - gap * .61f); l <= x - gap * .34f; l++) {
            for (int r = Math.round(x + gap * .12f); r <= x + gap * .40f; r++) {
                if (r - l < gap * .49f || r - l > gap * .93f) continue;
                if (!spine(ink, l, y - gap * 1.43f, y + gap * .46f)
                        || !spine(ink, r, y - gap * .89f, y + gap * 1.24f)) continue;
                boolean top = bridge(ink, l, r, y - gap * .92f, y - gap * .50f, staff);
                boolean bottom = bridge(ink, l, r, y + gap * .18f, y + gap * .48f, staff);
                int middle = (l + r) / 2;
                if (top && bottom && !ink.dark(middle, Math.round(y - gap * .22f))
                        && !ink.dark(middle, Math.round(y + gap * .06f))
                        && !ink.dark(l, Math.round(y + gap))) return true;
            }
        }
        return false;
    }

    private static boolean spine(Ink ink, int x, float top, float bottom) {
        int first = Math.round(top), last = Math.round(bottom), count = 0;
        for (int y = first; y <= last; y++) if (ink.dark(x, y)) count++;
        return count >= (last - first + 1) * .9f;
    }

    private static boolean bridge(Ink ink, int left, int right, float top, float bottom,
            PlayingTechniqueDetector.Staff staff) {
        for (int y = Math.round(top); y <= Math.round(bottom); y++) {
            float phase = (y - staff.top()) / staff.gap();
            if (phase >= -.15f && phase <= 4.15f && Math.abs(phase - Math.round(phase)) < .15f) continue;
            int count = 0;
            for (int x = left; x <= right; x++) if (ink.core(x, y)) count++;
            if (count >= (right - left + 1) * .84f) return true;
        }
        return false;
    }

    private static PlayingTechniqueDetector.Staff staff(Ink ink, MeasureRegion box, float x) {
        float g = gap(box, ink.height());
        var rails = DottedDyadMeasureSeparator.localRails(ink.gray(), ink.width(), ink.height(), Math.round(x),
                box.bottom() * ink.height() - g * 2.1f, g);
        return rails == null ? null
                : new PlayingTechniqueDetector.Staff(rails[0] - 4 * rails[1], rails[0], rails[1], 0, 1);
    }

    private static float gap(MeasureRegion m, int h) {
        return (m.bottom() - m.top()) * h / 8.2f;
    }

    private static boolean sameRow(MeasureRegion a, MeasureRegion b, int h, float gap) {
        return a.right() < b.left() && Math.abs(a.top() - b.top()) * h <= gap * .48f
                && Math.abs(a.bottom() - b.bottom()) * h <= gap * .48f;
    }

    private static boolean span(MeasureRegion m, int w, float gap, float min, float max) {
        float size = (m.right() - m.left()) * w;
        return size >= gap * min && size <= gap * max;
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1 && n.staffIndex() == 0 && n.augmentationDots() == 0
                && n.tupletDivisor() == 1 && n.tupletNormalNotes() == 1 && !n.tiedFromPrevious()
                && n.boundaryTies() == 0 && !n.crossStaffBeam() && n.leadingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static ScoreNoteEvent plainSixteenth(ScoreNoteEvent n) {
        return new ScoreNoteEvent(n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(),
                n.staffCount(), n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), 2, n.writtenAccidental(),
                0, n.tupletDivisor(), 0, n.articulations(), n.clefBottomDiatonic(), n.crossStaffBeam(),
                n.leadingRestBeats(), n.compactOpening(), n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(),
                n.stemDirection(), n.kind(), n.visualSize(), n.harmonicSemitones(), n.openStringTouch());
    }
}
