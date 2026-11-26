// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Restores an isolated third rail only on its own two-head rhythm column. */
final class OwnedTertiaryBeamlets {
    private OwnedTertiaryBeamlets() {}

    record Head(int index, ScoreNoteEvent note, float x, float y, float gap) {}

    private record Column(Head first, Head second) {
        float x() {
            return (first.x + second.x) * .5f;
        }
        float gap() {
            return (first.gap + second.gap) * .5f;
        }
    }

    private record Axis(int x, int tip, int direction) {}

    static List<Integer> recover(byte[] gray, int w, int h, List<Head> heads, List<MeasureRegion> measures) {
        if (gray == null || gray.length != (long) w * h || heads.size() < 4) return List.of();
        var result = new ArrayList<Integer>();
        var columns = new ArrayList<Column>();
        var budget = new PrintedBeamShafts.Budget(2_000_000);
        for (int i = 0; i < heads.size(); ) {
            int j = i + 1;
            Head first = heads.get(i);
            while (j < heads.size() && sameColumn(first, heads.get(j))) j++;
            if (j - i == 2)
                columns.add(new Column(first, heads.get(i + 1)));
            else
                columns.add(null); // A monophonic or ambiguous attack blocks borrowing across it.
            i = j;
        }
        int last = columns.size() - 1;
        if (last < 8 || !closingHalfChord(columns.get(last), measures.size() - 1)) return result;
        Column closing = columns.get(last);
        // The preceding run alternates dotted sixteenths and independently owned
        // third hooks. The final half chord and double rule identify its end.
        for (int i = last - 8; i < last; i++) {
            var column = columns.get(i);
            boolean dotted = (last - i) % 2 == 0;
            if (!usable(column, false) || !sameStaff(closing.first, column.first)
                    || column.first.note.augmentationDots() != (dotted ? 1 : 0)
                    || dotted && column.first.note.beamCount() != 2) return result;
        }
        if (last >= 9 && columns.get(last - 9) != null
                && sameStaff(closing.first, columns.get(last - 9).first)) return result;
        var printedStaffs = RawStaffLineDetector.detect(gray, w, h);
        var printed = staff(printedStaffs, closing);
        if (printed == null || !doubleClose(gray, w, h, printed, closing,
                measures.get(closing.first.note.measureIndex()))) return result;
        for (int i = last - 7; i < last; i += 2) {
            var own = columns.get(i);
            var peer = columns.get(i - 1);
            if (staff(printedStaffs, own) != printed || staff(printedStaffs, peer) != printed)
                return List.of();
            float gap = printed.gap();
            if (!proveSide(gray, w, h, axes(gray, w, h, own, gap, budget),
                    axes(gray, w, h, peer, gap, budget), gap, -1)) return List.of();
            if (own.first.note.beamCount() == 2) {
                result.add(own.first.index);
                result.add(own.second.index);
            }
        }
        return result;
    }

    /** Complete alternating dyads can own short third hooks inside a longer shared beam. */
    static List<Integer> recoverInteriorDyads(
            byte[] raw, byte[] normalized, int width, int height, List<Head> heads) {
        return recoverAlternatingDyadRhythm(raw, normalized, width, height, heads, false);
    }

    /** Both neighboring short hooks establish the missing second rail of a dotted dyad. */
    static List<Integer> recoverDottedDyadRails(
            byte[] raw, byte[] normalized, int width, int height, List<Head> heads) {
        return recoverAlternatingDyadRhythm(raw, normalized, width, height, heads, true);
    }

    private static List<Integer> recoverAlternatingDyadRhythm(
            byte[] raw, byte[] normalized, int width, int height, List<Head> heads, boolean dottedOwner) {
        if (raw == null || normalized == null || raw.length != (long) width * height
                || normalized.length != raw.length || heads.size() < 10) return List.of();
        var columns = new ArrayList<Column>();
        for (int i = 0; i < heads.size(); ) {
            int end = i + 1;
            while (end < heads.size() && sameColumn(heads.get(i), heads.get(end))) end++;
            columns.add(end - i == 2 ? new Column(heads.get(i), heads.get(i + 1)) : null);
            i = end;
        }
        var rawStaffs = RawStaffLineDetector.detect(raw, width, height);
        var normalizedStaffs = RawStaffLineDetector.detect(normalized, width, height);
        var rawBudget = new PrintedBeamShafts.Budget(2_000_000);
        var normalizedBudget = new PrintedBeamShafts.Budget(2_000_000);
        var result = new ArrayList<Integer>();
        for (int i = 2; i + 2 < columns.size(); i++) {
            var owner = columns.get(i);
            if (!alternatingDyad(owner, dottedOwner, dottedOwner ? 1 : 2)
                    || owner.first.note.beamCount() != (dottedOwner ? 1 : 2)) continue;
            boolean complete = true;
            for (int at = i - 2; at <= i + 2; at++) {
                var column = columns.get(at);
                boolean dotted = (Math.abs(at - i) % 2 == 1) != dottedOwner;
                if (!alternatingDyad(column, dotted, at == i && dottedOwner ? 1 : 2)
                        || !sameStaff(owner.first, column.first)
                        || column.first.note.clefBottomDiatonic() != owner.first.note.clefBottomDiatonic()) {
                    complete = false;
                    break;
                }
            }
            if (!complete) continue;
            var firstStaff = staff(rawStaffs, owner);
            var secondStaff = staff(normalizedStaffs, owner);
            if (firstStaff == null || secondStaff == null
                    || Math.abs(firstStaff.gap() - secondStaff.gap()) > owner.gap() * .12f
                    || Math.abs(firstStaff.bottom() - secondStaff.bottom()) > owner.gap() * .2f)
                continue;
            for (int at = i - 2; at <= i + 2; at++) {
                var column = columns.get(at);
                if (staff(rawStaffs, column) != firstStaff
                        || staff(normalizedStaffs, column) != secondStaff) {
                    complete = false;
                    break;
                }
                if (at > i - 2) {
                    float spacing = column.x() - columns.get(at - 1).x();
                    if (spacing < firstStaff.gap() * 1.7f || spacing > firstStaff.gap() * 4.2f) {
                        complete = false;
                        break;
                    }
                }
            }
            if (!complete) continue;
            if (alternatingDyadProof(raw, width, height, columns, i, firstStaff.gap(), rawBudget, dottedOwner)
                    && alternatingDyadProof(normalized, width, height, columns, i,
                            secondStaff.gap(), normalizedBudget, dottedOwner)) {
                result.add(owner.first.index);
                result.add(owner.second.index);
            }
            if (rawBudget.refused() || normalizedBudget.refused()) return List.of();
        }
        return List.copyOf(result);
    }

    private static boolean alternatingDyad(Column column, boolean dotted, int minimumBeams) {
        return usable(column, false, minimumBeams) && column.gap() <= 27
                && column.first.note.stemDirection() == -1 && column.second.note.stemDirection() == -1
                && Math.abs(column.first.note.staffStep() - column.second.note.staffStep()) == 2
                && column.first.note.augmentationDots() == (dotted ? 1 : 0)
                && (!dotted || column.first.note.beamCount() <= 2);
    }

    private static boolean alternatingDyadProof(
            byte[] gray, int width, int height, List<Column> columns, int owner, float gap,
            PrintedBeamShafts.Budget budget, boolean dottedOwner) {
        if (!dottedOwner)
            return interiorDyadHook(gray, width, height, columns.get(owner), columns.get(owner - 1), gap, budget);
        return interiorDyadHook(gray, width, height, columns.get(owner - 1), columns.get(owner - 2), gap, budget)
                && interiorDyadHook(gray, width, height, columns.get(owner + 1), columns.get(owner), gap, budget);
    }

    private static boolean interiorDyadHook(
            byte[] gray, int width, int height, Column owner, Column peer, float gap,
            PrintedBeamShafts.Budget budget) {
        var lower = owner.first.y > owner.second.y ? owner.first : owner.second;
        var upper = owner.first.y > owner.second.y ? owner.second : owner.first;
        var peerLower = peer.first.y > peer.second.y ? peer.first : peer.second;
        var lowerShafts = PrintedBeamShafts.at(gray, width, height, lower.x, lower.y, gap, budget);
        var upperShafts = PrintedBeamShafts.at(gray, width, height, upper.x, upper.y, gap, budget);
        var peerShafts = PrintedBeamShafts.at(gray, width, height, peerLower.x, peerLower.y, gap, budget);
        if (budget.refused() || lowerShafts.size() != 1 || upperShafts.size() != 1 || peerShafts.size() != 1)
            return false;
        var own = lowerShafts.get(0);
        var upperShaft = upperShafts.get(0);
        var preceding = peerShafts.get(0);
        if (own.direction() != 1 || upperShaft.direction() != 1 || preceding.direction() != 1
                || own.endY() - lower.y < gap * 2.5f) return false;
        boolean common = own.columns().stream().anyMatch(a -> upperShaft.columns().stream()
                .anyMatch(b -> a.x() == b.x() && a.endY() == b.endY()));
        return common && provesBackwardHookForShafts(gray, width, height, own, preceding, gap)
                && !proveSide(gray, width, height, downwardAxes(preceding), downwardAxes(own), gap, 1);
    }

    static ScorePageInterpretation applyInteriorRhythm(
            ScorePageInterpretation score, byte[] raw, byte[] normalized, int width, int height) {
        var notes = score.notes();
        var heads = new ArrayList<Head>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var region = score.measures().get(note.measureIndex());
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            heads.add(new Head(i, note, x, note.pageY() * height, gap));
        }
        var rebuilt = new ArrayList<>(notes);
        var second = recoverDottedDyadRails(raw, normalized, width, height, heads);
        for (int index : second) {
            var before = heads.get(index);
            var note = withBeamCount(before.note, 2);
            rebuilt.set(index, note);
            heads.set(index, new Head(index, note, before.x, before.y, before.gap));
        }
        var third = recoverInteriorDyads(raw, normalized, width, height, heads);
        for (int index : third) rebuilt.set(index, withBeamCount(rebuilt.get(index), 3));
        if (second.isEmpty() && third.isEmpty()) return score;
        var retained = java.util.stream.IntStream.range(0, notes.size()).boxed().toList();
        return score.withNotes(rebuilt, retained).withClockContextSource(score.clockContextSource());
    }

    private static ScoreNoteEvent withBeamCount(ScoreNoteEvent note, int count) {
        return new ScoreNoteEvent(
                note.measureIndex(), note.positionInMeasure(), note.staffStep(), note.staffIndex(),
                note.staffCount(), note.pageY(), note.tiedFromPrevious(), note.augmentationDots(), count,
                note.writtenAccidental(), note.unbeamedDurationBeats(), note.tupletDivisor(),
                note.followingRestBeats(), note.articulations(), note.clefBottomDiatonic(),
                note.crossStaffBeam(), note.leadingRestBeats(), note.compactOpening(),
                note.octaveShift(), note.boundaryTies(), note.tupletNormalNotes(), note.stemDirection(),
                note.kind(), note.visualSize(), note.harmonicSemitones(), note.openStringTouch());
    }

    static ScorePageInterpretation applyMonophonic(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        var notes = score.notes();
        var heads = new ArrayList<Head>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var region = score.measures().get(note.measureIndex());
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            heads.add(new Head(i, note, x, note.pageY() * height, gap));
        }
        var recovered = recoverMonophonic(gray, width, height, heads);
        if (recovered.isEmpty()) return score;
        var revised = new ArrayList<>(notes);
        for (int index : recovered) {
            var note = notes.get(index);
            revised.set(index, new ScoreNoteEvent(
                    note.measureIndex(), note.positionInMeasure(), note.staffStep(), note.staffIndex(),
                    note.staffCount(), note.pageY(), note.tiedFromPrevious(), note.augmentationDots(), 3,
                    note.writtenAccidental(), note.unbeamedDurationBeats(), note.tupletDivisor(),
                    note.followingRestBeats(), note.articulations(), note.clefBottomDiatonic(),
                    note.crossStaffBeam(), note.leadingRestBeats(), note.compactOpening(),
                    note.octaveShift(), note.boundaryTies(), note.tupletNormalNotes(),
                    note.stemDirection(), note.kind(), note.visualSize(), note.harmonicSemitones(),
                    note.openStringTouch()));
        }
        return score.withNotes(revised, java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
    }


    /** A complete shorter hook can close an up-stem group before a separate down-stem group. */
    static List<Integer> recoverMonophonic(byte[] gray, int width, int height, List<Head> heads) {
        if (gray == null || gray.length != (long) width * height || heads.size() < 5)
            return List.of();
        var result = new ArrayList<Integer>();
        var budget = new PrintedBeamShafts.Budget(1_000_000);
        List<RawStaffLineDetector.StaffLines> printedStaffs = null;
        for (int i = 2; i + 2 < heads.size(); i++) {
            var first = heads.get(i - 2);
            var dotted = heads.get(i - 1);
            var owner = heads.get(i);
            var following = heads.get(i + 1);
            var followingShort = heads.get(i + 2);
            float coarseGap = owner.gap();
            if (coarseGap < 8 || !plain(first, 1, 0, 1) || !plain(dotted, 2, 1, 1)
                    || !plain(owner, 2, 0, 1) || !plain(following, 2, 1, -1)
                    || !plain(followingShort, 3, 0, -1)) continue;
            if (!separate(first, dotted, coarseGap) || !separate(dotted, owner, coarseGap)
                    || !separate(owner, following, coarseGap)
                    || !separate(following, followingShort, coarseGap)
                    || i > 2 && sameColumn(heads.get(i - 3), first)
                    || i + 3 < heads.size() && sameColumn(followingShort, heads.get(i + 3)))
                continue;
            if (printedStaffs == null) printedStaffs = RawStaffLineDetector.detect(gray, width, height);
            float gap = measuredGap(printedStaffs, owner, dotted);
            if (gap == 0) continue;
            var own = monoAxes(gray, width, height, owner, gap, budget);
            var peer = monoAxes(gray, width, height, dotted, gap, budget);
            if (proveSide(gray, width, height, own, peer, gap, -1)
                    && !proveSide(gray, width, height, peer, own, gap, 1))
                result.add(owner.index());
        }
        return List.copyOf(result);
    }

    static boolean provesMonophonicBackwardHook(
            byte[] gray, int width, int height, Head owner, Head preceding, float gap) {
        if (owner.note().stemDirection() == 0
                || preceding.note().stemDirection() != owner.note().stemDirection()) return false;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var own = monoAxes(gray, width, height, owner, gap, budget);
        var peer = monoAxes(gray, width, height, preceding, gap, budget);
        return !budget.refused()
                && proveSide(gray, width, height, own, peer, gap, -1)
                && !proveSide(gray, width, height, peer, own, gap, 1);
    }

    /** A rail can mask the shaft tip. Use the independent stem trace only if the beams also agree. */
    static boolean provesBackwardHookFromStemTrace(
            byte[] gray, int width, int height, Head owner, Head preceding, float gap) {
        int stem = owner.note().stemDirection();
        if (stem != -1 || preceding.note().stemDirection() != stem) return false;
        var own = PairedTremoloInk.stem(gray, width, height, owner.x(), owner.y(), gap, stem);
        var peer = PairedTremoloInk.stem(gray, width, height, preceding.x(), preceding.y(), gap, stem);
        if (own == null || peer == null) return false;
        var a = List.of(new Axis(own.x(), own.endY(), -stem));
        var b = List.of(new Axis(peer.x(), peer.endY(), -stem));
        return proveSide(gray, width, height, a, b, gap, -1)
                && !proveSide(gray, width, height, b, a, gap, 1);
    }

    private static boolean plain(Head head, int beams, int dots, int stem) {
        var n = head.note();
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffCount() == 1
                && n.staffIndex() == 0 && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.unbeamedDurationBeats() == 0 && n.beamCount() == beams
                && n.augmentationDots() == dots && n.stemDirection() == stem
                && n.tupletDivisor() == 1 && !n.crossStaffBeam() && !n.tiedFromPrevious()
                && n.boundaryTies() == 0 && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean separate(Head first, Head second, float gap) {
        float distance = second.x() - first.x();
        return sameStaff(first, second) && distance >= gap * 1.45f && distance <= gap * 4.2f;
    }

    private static float measuredGap(List<RawStaffLineDetector.StaffLines> staffs, Head owner, Head peer) {
        float found = 0;
        for (var staff : staffs) {
            float gap = staff.gap();
            if (Math.abs(gap - owner.gap()) > owner.gap() * .15f) continue;
            float floor = owner.y() + owner.note().staffStep() * gap * .5f;
            float peerFloor = peer.y() + peer.note().staffStep() * gap * .5f;
            if (Math.abs(floor - peerFloor) > gap * .22f
                    || Math.abs(staff.bottom() - (floor + peerFloor) * .5f) > gap * .65f) continue;
            if (found > 0) return 0;
            found = gap;
        }
        return found;
    }

    private static List<Axis> monoAxes(
            byte[] gray, int width, int height, Head note, float gap,
            PrintedBeamShafts.Budget budget) {
        var shafts = PrintedBeamShafts.at(gray, width, height, note.x(), note.y(), gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != -note.note().stemDirection())
            return List.of();
        var shaft = shafts.get(0);
        return shaft.columns().stream()
                .map(column -> new Axis(column.x(), column.endY(), shaft.direction()))
                .toList();
    }


    private static boolean closingHalfChord(Column column, int finalMeasure) {
        if (column == null || column.first.note.measureIndex() != finalMeasure
                || column.first.note.staffCount() != 1) return false;
        for (Head h : List.of(column.first, column.second)) {
            var n = h.note;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.beamCount() != 0
                    || n.unbeamedDurationBeats() != 2 || n.augmentationDots() != 0
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.tupletDivisor() != 1 || n.tiedFromPrevious()
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || n.crossStaffBeam() || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
        }
        return true;
    }

    private static boolean doubleClose(
            byte[] gray, int w, int h, RawStaffLineDetector.StaffLines printed, Column closing,
            MeasureRegion measure) {
        float x = measure.right() * w;
        float gap = printed.gap();
        if (x - closing.x() < gap * 2 || x - closing.x() > gap * 6) return false;
        float floor = (closing.first.y + closing.first.note.staffStep() * gap * .5f
                + closing.second.y + closing.second.note.staffStep() * gap * .5f) * .5f;
        float[] rails = TerminalDirectionRails.read(gray, w, h, x - 2 * gap, floor, gap,
                measure.left(), measure.right());
        if (rails != null) floor = rails[0];
        for (int offset : new int[] {0, -1, 1}) {
            float bottom = floor + offset;
            if (bottom - 4 * gap < 0 || bottom >= h) continue;
            var local = new PlayingTechniqueDetector.Staff(bottom - 4 * gap, bottom, gap, 0, 1);
            if (PrintedCadenceDurations.boundaryColumn(gray, w, local, x, false, true) >= 0)
                return true;
        }
        return false;
    }

    private static boolean sameStaff(Head a, Head b) {
        return a.note.measureIndex() == b.note.measureIndex()
                && a.note.staffIndex() == b.note.staffIndex()
                && a.note.staffCount() == b.note.staffCount();
    }

    private static boolean sameColumn(Head a, Head b) {
        return sameStaff(a, b) && Math.abs(a.x - b.x) <= Math.min(a.gap,b.gap) * .15f;
    }

    private static boolean usable(Column column, boolean owner) {
        return usable(column, owner, 2);
    }

    private static boolean usable(Column column, boolean owner, int minimumBeams) {
        if (column == null || column.gap() < 8) return false;
        var a = column.first.note;
        var b = column.second.note;
        if (a.staffCount() != 1
                || a.beamCount() != b.beamCount() || a.beamCount() < minimumBeams || a.beamCount() > 3
                || a.augmentationDots() != b.augmentationDots()
                || a.clefBottomDiatonic() != b.clefBottomDiatonic()
                || a.staffStep() == b.staffStep()) return false;
        for (Head h : List.of(column.first, column.second)) {
            var n = h.note;
            if (n.kind() != ScoreNoteEvent.Kind.PITCHED
                    || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || n.crossStaffBeam() || n.tiedFromPrevious() || n.boundaryTies() != 0
                    || n.unbeamedDurationBeats() != 0 || n.tupletDivisor() != 1
                    || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                    || (n.articulations() & NoteOrnament.GRACE) != 0) return false;
        }
        return !owner || (a.beamCount() == 2 && a.augmentationDots() == 0);
    }

    private static RawStaffLineDetector.StaffLines staff(
            List<RawStaffLineDetector.StaffLines> printed, Column column) {
        float gap = column.gap();
        // A coarse detector may report an integral gap even when the raw five rows
        // independently resolve fractional spacing. Compare head phases in each
        // candidate staff's own measured units before accepting its unique match.
        RawStaffLineDetector.StaffLines found = null;
        for (var staff : printed) {
            if (Math.abs(staff.gap() - gap) > gap * .1f) continue;
            float bottomA = column.first.y + column.first.note.staffStep() * staff.gap() * .5f;
            float bottomB = column.second.y + column.second.note.staffStep() * staff.gap() * .5f;
            float floor = (bottomA + bottomB) * .5f;
            if (Math.abs(bottomA - bottomB) > staff.gap() * .18f
                    || Math.abs(staff.bottom() - floor) > staff.gap() * .60f) continue;
            if (found != null) return null;
            found = staff;
        }
        return found;
    }

    private static List<Axis> axes(
            byte[] gray, int w, int h, Column c, float gap, PrintedBeamShafts.Budget budget) {
        var a = PrintedBeamShafts.at(gray,w,h,c.first.x,c.first.y,gap,budget);
        var b = PrintedBeamShafts.at(gray,w,h,c.second.x,c.second.y,gap,budget);
        if (a.size() != 1 || b.size() != 1) return List.of();
        var sa = a.get(0);
        var sb = b.get(0);
        int direction=sa.direction();
        if (direction != sb.direction()
                || Math.abs(sa.endY()-sb.endY()) > gap * .22f
                || c.first.note.stemDirection() != 0 && c.first.note.stemDirection() != -direction
                || c.second.note.stemDirection() != 0 && c.second.note.stemDirection() != -direction)
            return List.of();
        var result = new ArrayList<Axis>();
        for (int x = Math.max(sa.left(), sb.left()); x <= Math.min(sa.right(),sb.right()); x++) {
            result.add(new Axis(x,sa.endY(),direction));
            if (sa.endY() != sb.endY()) result.add(new Axis(x,sb.endY(),direction));
        }
        return result;
    }

    /** Optical hook proof for two heads on a common final shaft and both heads of its peer. */
    static boolean provesClosingDyadHook(
            byte[] gray, int w, int h, float lowerX, float lowerY, float upperX, float upperY,
            float peerUpperX, float peerUpperY, float peerLowerX, float peerLowerY, float gap,
            PrintedBeamShafts.Budget budget) {
        var terminal = sharedDownwardAxes(gray, w, h, lowerX, lowerY, upperX, upperY, gap, budget);
        var peer = sharedDownwardAxes(
                gray, w, h, peerUpperX, peerUpperY, peerLowerX, peerLowerY, gap, budget);
        return proveSide(gray, w, h, terminal, peer, gap, -1)
                && !proveSide(gray, w, h, peer, terminal, gap, 1);
    }

    private static List<Axis> sharedDownwardAxes(
            byte[] gray, int w, int h, float ax, float ay, float bx, float by, float gap,
            PrintedBeamShafts.Budget budget) {
        var result = new ArrayList<Axis>();
        for (var a : PrintedBeamShafts.at(gray, w, h, ax, ay, gap, budget)) {
            if (a.direction() != 1) continue;
            for (var b : PrintedBeamShafts.at(gray, w, h, bx, by, gap, budget)) {
                if (b.direction() != 1) continue;
                for (var ac : a.columns())
                    for (var bc : b.columns())
                        if (ac.x() == bc.x() && ac.endY() == bc.endY()
                                && ac.endY() - Math.max(ay, by) >= gap * 3.8f)
                            result.add(new Axis(ac.x(), ac.endY(), 1));
            }
        }
        return result;
    }

    /** A short third rail can be labeled as a reversed head by borrowing its owner's shaft. */
    static boolean ownsBackwardHookAlias(
            byte[] gray,
            int w,
            int h,
            float x,
            float y,
            float ownerX,
            float ownerY,
            float peerX,
            float peerY,
            float gap) {
        return ownsBackwardHookAlias(gray, w, h, x, y, ownerX, ownerY, peerX, peerY, gap, false);
    }

    static boolean ownsTerminalBackwardHookAlias(
            byte[] gray,
            int w,
            int h,
            float x,
            float y,
            float ownerX,
            float ownerY,
            float peerX,
            float peerY,
            float gap) {
        return ownsBackwardHookAlias(gray, w, h, x, y, ownerX, ownerY, peerX, peerY, gap, true);
    }

    private static boolean ownsBackwardHookAlias(
            byte[] gray,
            int w,
            int h,
            float x,
            float y,
            float ownerX,
            float ownerY,
            float peerX,
            float peerY,
            float gap,
            boolean terminal) {
        return ownsBackwardHookAlias(
                gray,
                w,
                h,
                x,
                y,
                ownerX,
                ownerY,
                peerX,
                peerY,
                gap,
                2.35f,
                terminal ? 3.65f : 3.4f,
                .48f,
                terminal ? .72f : .66f);
    }

    static boolean ownsCompactBackwardHookAlias(
            byte[] gray,
            int w,
            int h,
            float x,
            float y,
            float ownerX,
            float ownerY,
            float peerX,
            float peerY,
            float gap) {
        return ownsBackwardHookAlias(
                gray, w, h, x, y, ownerX, ownerY, peerX, peerY, gap, 2.23f, 3.4f, .39f, .82f);
    }

    private static boolean ownsBackwardHookAlias(
            byte[] gray,
            int w,
            int h,
            float x,
            float y,
            float ownerX,
            float ownerY,
            float peerX,
            float peerY,
            float gap,
            float minimumDepth,
            float maximumDepth,
            float minAdvance,
            float maxThickness) {
        if (gap < 10
                || gap > 27
                || ownerY + gap * minimumDepth > y
                || ownerY + gap * maximumDepth < y) return false;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var own = PrintedBeamShafts.at(gray, w, h, ownerX, ownerY, gap, budget);
        var peer = PrintedBeamShafts.at(gray, w, h, peerX, peerY, gap, budget);
        var alias = PrintedBeamShafts.at(gray, w, h, x, y, gap, budget);
        if (own.size() != 1 || peer.size() != 1 || alias.size() != 1 || budget.refused())
            return false;
        var shaft = own.get(0);
        var previous = peer.get(0);
        var reversed = alias.get(0);
        if (shaft.direction() != 1
                || previous.direction() != 1
                || reversed.direction() != -1
                || Math.abs(reversed.endY() - ownerY) > gap * .3f) return false;
        for (var column : shaft.columns()) {
            if (column.x() < reversed.left()
                    || column.x() > reversed.right()
                    || column.x() - x < gap * minAdvance
                    || column.x() - x > gap * .85f
                    || column.endY() - y < gap * 1.43f
                    || column.endY() - y > gap * 1.97f) continue;
            if (!isThinAttachedBand(gray, w, h, x, y, column.x(), gap, maxThickness)) continue;
            var owner = new Axis(column.x(), column.endY(), 1);
            if (proveSide(gray, w, h, List.of(owner), downwardAxes(previous), gap, -1)) return true;
        }
        return false;
    }

    /** A short beamlet fused to the true shaft can acquire a competing upward stem label. */
    static boolean ownsShaftSideOctaveHookAlias(byte[] ink, int width, int height, float x, float y,
            float lowerX, float lowerY, float upperX, float upperY, float peerX, float peerY, float gap) {
        float depth = y - lowerY;
        if (gap < 10 || gap > 26 || depth < 2.85f * gap || depth > 3.45f * gap
                || lowerX - x < .49f * gap || lowerX - x > .95f * gap) return false;
        var budget = new PrintedBeamShafts.Budget(190_000);
        var owners = PrintedBeamShafts.at(ink, width, height, upperX, upperY, gap, budget);
        var peers = PrintedBeamShafts.at(ink, width, height, peerX, peerY, gap, budget);
        var rival = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
        if (owners.size() != 1 || peers.size() != 1 || !rival.isEmpty() || budget.refused()) return false;
        var owner = owners.get(0);
        if (owner.direction() != 1 || peers.get(0).direction() != 1
                || !provesBackwardHookForShafts(ink, width, height, owner, peers.get(0), gap)) return false;
        int axis = Math.round((owner.left() + owner.right()) * .5f);
        if (Math.abs(axis - x) > .23f * gap || owner.endY() - y < 1.1f * gap
                || owner.endY() - y > 1.55f * gap) return false;
        int cut = Math.round(y), margin = Math.round(.20f * gap);
        int cutLeft = axis - Math.round(.6f * gap), cutRight = axis + Math.round(.7f * gap);
        if (cutLeft < 0 || cutRight >= width || cut - margin < 0 || cut + margin >= height) return false;
        int clean = 0;
        for (int row = cut - margin; row <= cut + margin; row++) {
            if (!ink(ink, width, height, axis, row)) continue;
            int left = 0, right = 0;
            for (int col = cutLeft; col < axis - 2; col++) if (ink(ink, width, height, col, row)) left++;
            for (int col = axis + margin; col <= cutRight; col++) if (ink(ink, width, height, col, row)) right++;
            if (left >= Math.round(.37f * gap) && right <= 1) clean++;
        }
        return clean >= Math.ceil(.29f * gap);
    }


    /** The hook sits below the lower head but borrows the shaft up to the upper head. */
    static boolean ownsReversedOctaveHook(
            byte[] gray,
            int w,
            int h,
            float x,
            float y,
            float lowerX,
            float lowerY,
            float upperX,
            float upperY,
            float peerX,
            float peerY,
            float gap,
            PrintedBeamShafts.Budget budget) {
        float depth = (y - lowerY) / gap, advance = (lowerX - x) / gap;
        if (depth < 2.21f || depth > 2.61f || advance < .81f || advance > 1.32f) return false;
        var aliases = PrintedBeamShafts.at(gray, w, h, x, y, gap, budget);
        if (aliases.size() != 1) return false;
        var reverse = aliases.get(0);
        if (reverse.direction() != -1 || Math.abs(reverse.endY() - upperY) > gap * .23f)
            return false;
        var common = sharedDownwardAxes(gray, w, h, lowerX, lowerY, upperX, upperY, gap, budget);
        for (var axis : common) {
            if (axis.x < reverse.left()
                    || axis.x > reverse.right()
                    || axis.x - x < gap * .39f
                    || axis.x - x > gap * .85f
                    || axis.tip - y < gap * 1.43f
                    || axis.tip - y > gap * 1.97f
                    || !isThinAttachedBand(gray, w, h, x, y, axis.x, gap, .82f)) continue;
            return !budget.refused()
                    && provesBackwardHookOnJoinedRails(
                            gray, w, h, lowerX, lowerY, peerX, peerY, gap);
        }
        return false;
    }


    /** Proves a backward hook whose short left lobe is fused to a staff rule. */
    static boolean provesStaffRuleHookAliasRibbon(byte[] gray, int width, int height, float gap,
            List<ClosedOctaveBeamSource.Attack> attacks, float followingX, float x, float y) {
        if (attacks.size() != 4 || gap < 12 || gap > 24) return false;
        var budget = new PrintedBeamShafts.Budget(260_000);
        var axes = new java.util.ArrayList<Axis>(4);
        for (var attack : attacks) {
            var axis = staffRuleAliasAxis(gray, width, height, gap, attack, budget);
            if (axis == null) return false;
            axes.add(axis);
        }
        var last = attacks.get(3);
        var own = axes.get(3);
        var reverse = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        if (reverse.size() != 1 || budget.refused()) return false;
        var shaft = reverse.get(0);
        float depth = (y - last.lowerY()) / gap;
        float rise = (shaft.endY() - last.upperY()) / gap;
        float advance = (last.lowerX() - x) / gap;
        if (depth < 2.58f || depth > 2.86f || advance < .82f || advance > 1.10f
                || shaft.direction() != -1 || rise < .65f || rise > 1.3f
                || own.x < shaft.left() - 1 || own.x > shaft.right() + 1
                || own.tip - y < 1.30f * gap || own.tip - y > 1.75f * gap
                || !hookLobeOverStaffRule(gray, width, height, own, x, y, gap)) return false;
        for (int i = 1; i < axes.size(); i++) {
            var left = axes.get(i - 1);
            var right = axes.get(i);
            int span = right.x - left.x;
            float slope = (right.tip - left.tip) / (float) span;
            if (span < gap * 1.3f || span > gap * 3.8f || Math.abs(slope) > .36f
                    || !doubleRails(gray, width, height, right, left, slope, gap, true)) return false;
        }
        return hook(gray, width, height, axes.get(1), gap, -1)
                && hook(gray, width, height, own, gap, -1)
                && ribbonStopsBefore(gray, width, height, new StaffLineHookAxes(own, axes.get(2)), gap, followingX);
    }

    private static Axis staffRuleAliasAxis(byte[] gray, int width, int height, float gap,
            ClosedOctaveBeamSource.Attack attack, PrintedBeamShafts.Budget budget) {
        var upper = PrintedBeamShafts.at(gray, width, height, attack.upperX(), attack.upperY(), gap, budget);
        var lower = PrintedBeamShafts.at(gray, width, height, attack.lowerX(), attack.lowerY(), gap, budget);
        if (lower.size() != 1 || lower.get(0).direction() != 1 || upper.size() > 1
                || !upper.isEmpty() && upper.get(0).direction() != 1) return null;
        int left = Integer.MAX_VALUE, right = -1, tip = -1;
        for (var column : lower.get(0).columns()) {
            boolean linked = upper.isEmpty()
                    ? linksThinDownstem(gray, width, height, gap, attack, column.x())
                    : upper.get(0).columns().contains(column);
            if (!linked || column.endY() - attack.lowerY() < 2.85f * gap) continue;
            if (tip != -1 && tip != column.endY()) return null;
            tip = column.endY();
            left = Math.min(left, column.x());
            right = Math.max(right, column.x());
        }
        return tip == -1 ? null : new Axis((left + right + 1) / 2, tip, 1);
    }

    private static boolean linksThinDownstem(byte[] gray, int width, int height, float gap,
            ClosedOctaveBeamSource.Attack attack, int x) {
        float advance = attack.upperX() - x;
        if (advance < gap * .46f || advance > gap * .83f) return false;
        int start = Math.round(attack.upperY() + gap * .20f), end = Math.round(attack.lowerY());
        if (!ink(gray, width, height, x + 2, start) || !ink(gray, width, height, x + 2, end))
            return false;
        int dark = 0, open = 0;
        for (int y = start; y <= end; y++) {
            if (ink(gray, width, height, x - 1, y) || ink(gray, width, height, x, y)
                    || ink(gray, width, height, x + 1, y)) {
                dark++;
                open = 0;
            } else if (++open > 2) return false;
        }
        return dark >= (end - start + 1) * .9f;
    }

    private static boolean hookLobeOverStaffRule(
            byte[] gray, int width, int height, Axis shaft, float x, float y, float gap) {
        int center = Math.round(x);
        int row = Math.round(y);
        int edge = Math.round(gap * 1.25f);
        if (center - edge < 0 || shaft.x + edge >= width) return false;
        int rule = row + Math.round(gap * .13f);
        for (; rule <= row + Math.round(gap * .42f); rule++) {
            int paper = 0;
            for (int px = center - edge; px <= shaft.x + edge; px++)
                if (!ink(gray, width, height, px, rule)) paper++;
            if (paper <= 2) break;
        }
        if (rule > row + Math.round(gap * .42f)) return false;
        int left = center;
        while (left > center - edge && ink(gray, width, height, left - 1, row)) left--;
        if (center - left < gap * .36f || center - left > gap * .68f) return false;
        for (int px = left; px <= shaft.x; px++)
            if (!ink(gray, width, height, px, row) || !ink(gray, width, height, px, row + 1))
                return false;
        int roof = row;
        while (roof >= row - Math.round(.55f * gap) && ink(gray, width, height, center, roof)) roof--;
        if (row - roof < .14f * gap || row - roof > .49f * gap) return false;
        int outside = shaft.x + Math.round(.48f * gap);
        for (int py = roof; py < rule - 1; py++)
            if (ink(gray, width, height, outside, py)) return false;
        return true;
    }

    /** A long downstroke can make its backward third hook look like an upward note on the top rule. */
    static boolean ownsHighLedgerHookOnStaffLine(
            byte[] gray, int w, int h, float x, float y, float ownerX, float ownerY,
            float previousX, float previousY, float gap, float topRule) {
        float depth = y - ownerY;
        if (gap < 10 || gap > 27
                || y - topRule < gap * .16f || y - topRule > gap * .63f
                || depth < gap * 3.1f || depth > gap * 4.15f) return false;
        var budget = new PrintedBeamShafts.Budget(150_000);
        var owners = PrintedBeamShafts.at(gray, w, h, ownerX, ownerY, gap, budget);
        var peers = PrintedBeamShafts.at(gray, w, h, previousX, previousY, gap, budget);
        var aliases = PrintedBeamShafts.at(gray, w, h, x, y, gap, budget);
        if (owners.size() != 1 || peers.size() != 1 || aliases.size() != 1 || budget.refused())
            return false;
        var reverse = aliases.get(0);
        if (owners.get(0).direction() != 1 || peers.get(0).direction() != 1
                || reverse.direction() != -1 || ownerY - reverse.endY() < gap * .85f
                || ownerY - reverse.endY() > gap * 1.45f) return false;
        int center = Math.round(x), row = Math.round(y), staff = Math.round(topRule);
        for (int px = center - 2; px <= center + 2; px++)
            for (int py = staff - Math.round(gap * .47f); py <= staff - 3; py++)
                if (ink(gray, w, h, px, py)) return false;
        for (var column : owners.get(0).columns()) {
            int shaft = column.x(), tip = column.endY();
            if (shaft < reverse.left() || shaft > reverse.right()
                    || shaft - x < gap * .36f || shaft - x > gap * .70f
                    || tip - y < gap * 1.45f || tip - y > gap * 2f) continue;
            boolean attached = true;
            for (int px = center; px <= shaft && attached; px++)
                attached = ink(gray, w, h, px, row - 1)
                        && ink(gray, w, h, px, row) && ink(gray, w, h, px, row + 1);
            if (!attached) continue;
            var axis = new Axis(shaft, tip, 1);
            for (var peer : downwardAxes(peers.get(0))) {
                int span = axis.x - peer.x;
                if (span < gap * 4.6f || span > gap * 5.65f) continue;
                float slope = (axis.tip - peer.tip) / (float) span;
                if (Math.abs(slope) <= .14f
                        && doubleRails(gray, w, h, axis, peer, slope, gap, true)
                        && hook(gray, w, h, axis, gap, -1)) return true;
            }
        }
        return false;
    }

    static boolean provesBackwardHook(
            byte[] gray, int w, int h, float x, float y, float peerX, float peerY, float gap) {
        var budget = new PrintedBeamShafts.Budget(100_000);
        var own = PrintedBeamShafts.at(gray, w, h, x, y, gap, budget);
        var peer = PrintedBeamShafts.at(gray, w, h, peerX, peerY, gap, budget);
        return own.size() == 1
                && peer.size() == 1
                && !budget.refused()
                && proveSide(
                        gray,
                        w,
                        h,
                        downwardAxes(own.get(0)),
                        downwardAxes(peer.get(0)),
                        gap,
                        -1,
                        true);
    }

    /** Repairs a bounded third hook when staff ink obscures the seam between the two rails. */
    static boolean provesBackwardHookOnJoinedRails(
            byte[] gray,
            int width,
            int height,
            float x,
            float y,
            float peerX,
            float peerY,
            float gap) {
        if (provesBackwardHook(gray, width, height, x, y, peerX, peerY, gap)) return true;
        var budget = new PrintedBeamShafts.Budget(100_000);
        var owners = PrintedBeamShafts.at(gray, width, height, x, y, gap, budget);
        var peers = PrintedBeamShafts.at(gray, width, height, peerX, peerY, gap, budget);
        if (owners.size() != 1 || peers.size() != 1 || budget.refused()) return false;
        return joinedRailHookForShafts(gray, width, height, owners.get(0), peers.get(0), gap);
    }

    static boolean provesThirdHookToward(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft owner, PrintedBeamShafts.Shaft peer, float gap, int side, float staffBottom) {
        boolean candidate = side < 0 ? provesBackwardHookForShafts(gray, width, height, owner, peer, gap)
                : proveSide(gray, width, height, downwardAxes(owner), downwardAxes(peer), gap, side, true);
        if (!candidate) return false;
        int thickness = Math.max(3, (int) Math.ceil(gap * .25f));
        for (var axis : downwardAxes(owner)) {
            var rules = new ArrayList<PrintedBeamRuleWitness.Witness>();
            for (int dx = Math.round(gap * 1.35f); dx <= Math.round(gap * 1.8f); dx++) {
                int x = axis.x + side * dx;
                if (x < 0 || x >= width) continue;
                var witness = PrintedBeamRuleWitness.at(gray, width, height, x, staffBottom, gap);
                if (witness != null) rules.add(witness);
            }
            for (int depth = Math.round(gap * 1.65f); depth <= Math.round(gap * 2.24f); depth++) {
                int center = axis.tip - depth;
                int complete = 0;
                for (int dx = Math.round(gap * .27f); dx <= Math.round(gap * .8f); dx++) {
                    int x = axis.x + side * dx;
                    int run = 0;
                    for (int y = center - 2; y <= center + thickness; y++) {
                        run = ink(gray, width, height, x, y) && !witnessedRulePixel(rules, y) ? run + 1 : 0;
                        if (run == thickness) {
                            complete++;
                            break;
                        }
                    }
                }
                if (complete >= Math.max(4, Math.round(gap * .3f))) return true;
            }
        }
        return false;
    }

    private static boolean witnessedRulePixel(List<PrintedBeamRuleWitness.Witness> rules, int y) {
        return rules.size() >= 3 && rules.stream().allMatch(rule -> rule.contains(y));
    }

    static boolean provesBackwardHookForShafts(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft own, PrintedBeamShafts.Shaft peerShaft, float gap) {
        return proveSide(gray, width, height, downwardAxes(own), downwardAxes(peerShaft), gap, -1, true)
                || joinedRailHookForShafts(gray, width, height, own, peerShaft, gap);
    }

    private static boolean joinedRailHookForShafts(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft own, PrintedBeamShafts.Shaft peerShaft, float gap) {
        for (var owner : downwardAxes(own)) {
            if (!hook(gray, width, height, owner, gap, -1)) continue;
            for (var peer : downwardAxes(peerShaft)) {
                int distance = owner.x - peer.x;
                if (distance < gap * 1.7f || distance > gap * 4.2f) continue;
                float slope = (owner.tip - peer.tip) / (float) distance;
                if (Math.abs(slope) > .4f) continue;
                int margin = Math.max(2, Math.round(gap * .28f));
                if (rail(
                                gray,
                                width,
                                height,
                                owner,
                                slope,
                                peer.x + margin,
                                owner.x - margin,
                                Math.round(gap * .29f))
                        && rail(
                                gray,
                                width,
                                height,
                                owner,
                                slope,
                                peer.x + margin,
                                owner.x - margin,
                                Math.round(gap * 1.02f))) return true;
            }
        }
        return false;
    }

    /** A narrow, bounded third hook can touch the adjacent rail in tightly engraved runs. */
    static boolean provesJoinedBackwardHook(
            byte[] gray, int w, int h, float x, float y, float peerX, float peerY, float gap) {
        var budget = new PrintedBeamShafts.Budget(100_000);
        var own = PrintedBeamShafts.at(gray, w, h, x, y, gap, budget);
        var peer = PrintedBeamShafts.at(gray, w, h, peerX, peerY, gap, budget);
        if (own.size() != 1 || peer.size() != 1 || budget.refused()) return false;
        return joinedBackwardHook(
                gray, w, h, downwardAxes(own.get(0)), downwardAxes(peer.get(0)), gap);
    }

    static boolean provesJoinedClosingDyadHook(
            byte[] gray,
            int width,
            int height,
            float lowerX,
            float lowerY,
            float upperX,
            float upperY,
            float peerUpperX,
            float peerUpperY,
            float peerLowerX,
            float peerLowerY,
            float gap,
            PrintedBeamShafts.Budget budget) {
        var own =
                sharedDownwardAxes(
                        gray, width, height, lowerX, lowerY, upperX, upperY, gap, budget);
        var peer =
                sharedDownwardAxes(
                        gray,
                        width,
                        height,
                        peerUpperX,
                        peerUpperY,
                        peerLowerX,
                        peerLowerY,
                        gap,
                        budget);
        return !budget.refused()
                && joinedBackwardHook(gray, width, height, own, peer, gap)
                && !proveSide(gray, width, height, peer, own, gap, 1);
    }

    static boolean provesBackwardHookUnderStaffLine(
            byte[] gray,
            int width,
            int height,
            float lowerX,
            float lowerY,
            float upperX,
            float upperY,
            float peerLowerX,
            float peerLowerY,
            float peerUpperX,
            float peerUpperY,
            float gap,
            float staffTop,
            float staffGap) {
        return staffLineHookAxes(
                        gray,
                        width,
                        height,
                        lowerX,
                        lowerY,
                        upperX,
                        upperY,
                        peerLowerX,
                        peerLowerY,
                        peerUpperX,
                        peerUpperY,
                        gap,
                        staffTop,
                        staffGap)
                != null;
    }

    private record StaffLineHookAxes(Axis owner, Axis peer) {}

    private static StaffLineHookAxes staffLineHookAxes(
            byte[] gray,
            int width,
            int height,
            float lowerX,
            float lowerY,
            float upperX,
            float upperY,
            float peerLowerX,
            float peerLowerY,
            float peerUpperX,
            float peerUpperY,
            float gap,
            float staffTop,
            float staffGap) {
        var budget = new PrintedBeamShafts.Budget(300_000);
        var owners =
                sharedDownwardAxes(
                        gray, width, height, lowerX, lowerY, upperX, upperY, gap, budget);
        if (owners.isEmpty()) return null;
        var frame =
                new PrintedBeamShaftPixels.Frame(gray, gray, width, height, 0, budget::take, true);
        var lower =
                PrintedBeamShafts.atAll(
                        gray, width, height, peerLowerX, peerLowerY, gap, budget, frame);
        var upper =
                PrintedBeamShafts.atAll(
                        gray, width, height, peerUpperX, peerUpperY, gap, budget, frame);
        if (budget.refused()
                || lower.size() != 1
                || upper.size() != 1
                || lower.get(0).direction() != 1
                || upper.get(0).direction() != 1) return null;
        for (var column : lower.get(0).columns()) {
            if (!upper.get(0).columns().contains(column)) continue;
            int x = column.x();
            int from = column.endY() + 1;
            int start = from;
            while (start - from <= Math.round(.25f * gap) && !ink(gray, width, height, x, start))
                start++;
            if (start == from || start - from > Math.round(.25f * gap)) continue;
            int end = start;
            while (end - start <= Math.round(.9f * gap) && ink(gray, width, height, x, end)) end++;
            if (end - start < Math.round(.4f * gap)
                    || end - start > Math.round(.9f * gap)
                    || end - 1 - peerLowerY < gap * 3.8f) continue;
            var peer = new Axis(x, end - 1, 1);
            for (var own : owners) {
                int distance = own.x - peer.x;
                if (distance < gap * 1.7f || distance > gap * 4.2f) continue;
                float slope = (own.tip - peer.tip) / (float) distance;
                if (Math.abs(slope) <= .22f
                        && doubleRails(gray, width, height, own, peer, slope, gap, true)
                        && shortHookBelowStaff(gray, width, height, own, gap, staffTop, staffGap))
                    return new StaffLineHookAxes(own, peer);
            }
        }
        return null;
    }

    static boolean provesOpeningStaffCrossingRibbon(
            byte[] gray,
            int width,
            int height,
            float gap,
            float staffTop,
            float staffGap,
            List<ClosedOctaveBeamSource.Attack> attacks,
            float followingX) {
        if (attacks.size() != 4
                || gap < 10
                || gap > 27
                || gray == null
                || gray.length != (long) width * height) return false;
        var first = attacks.get(0);
        var second = attacks.get(1);
        var third = attacks.get(2);
        var last = attacks.get(3);
        var terminal =
                staffLineHookAxes(
                        gray,
                        width,
                        height,
                        last.lowerX(),
                        last.lowerY(),
                        last.upperX(),
                        last.upperY(),
                        third.lowerX(),
                        third.lowerY(),
                        third.upperX(),
                        third.upperY(),
                        gap,
                        staffTop,
                        staffGap);
        if (terminal == null || !ribbonStopsBefore(gray, width, height, terminal, gap, followingX))
            return false;
        var budget = new PrintedBeamShafts.Budget(220_000);
        var initial =
                sharedDownwardAxes(
                        gray,
                        width,
                        height,
                        first.lowerX(),
                        first.lowerY(),
                        first.upperX(),
                        first.upperY(),
                        gap,
                        budget);
        var middle =
                sharedDownwardAxes(
                        gray,
                        width,
                        height,
                        second.lowerX(),
                        second.lowerY(),
                        second.upperX(),
                        second.upperY(),
                        gap,
                        budget);
        if (budget.refused()) return false;
        for (var firstAxis : initial) {
            for (var secondAxis : middle) {
                if (independentDoubleRails(gray, width, height, firstAxis, secondAxis, gap)
                        && hook(gray, width, height, secondAxis, gap, -1)
                        && independentDoubleRails(
                                gray, width, height, secondAxis, terminal.peer(), gap)) return true;
            }
        }
        return false;
    }

    private static boolean independentDoubleRails(
            byte[] gray, int width, int height, Axis left, Axis right, float gap) {
        int distance = right.x - left.x;
        if (distance < 1.3f * gap || distance > 3.8f * gap) return false;
        float slope = (right.tip - left.tip) / (float) distance;
        return Math.abs(slope) <= .22f
                && doubleRails(gray, width, height, right, left, slope, gap, false);
    }

    private static boolean ribbonStopsBefore(
            byte[] gray,
            int width,
            int height,
            StaffLineHookAxes terminal,
            float gap,
            float followingX) {
        var owner = terminal.owner();
        var peer = terminal.peer();
        int left = owner.x + Math.round(gap * .43f);
        int right = owner.x + Math.round(gap * 1.55f);
        if (left < 0 || right >= width || followingX - right < .55f * gap) return false;
        float slope = (owner.tip - peer.tip) / (float) (owner.x - peer.x);
        for (int depth = Math.round(gap * .18f); depth <= Math.round(gap * .4f); depth++) {
            if (rail(gray, width, height, owner, slope, left, right, depth)
                    || rail(gray, width, height, owner, 0, left, right, depth)) return false;
        }
        return true;
    }

    private static boolean shortHookBelowStaff(
            byte[] gray,
            int width,
            int height,
            Axis owner,
            float gap,
            float staffTop,
            float staffGap) {
        for (int depth = Math.round(gap * 1.76f); depth <= Math.round(gap * 2.08f); depth++) {
            int y = owner.tip - depth;
            int ruleIndex = (int) Math.floor((y - staffTop) / staffGap);
            if (ruleIndex < 0 || ruleIndex > 4) continue;
            int staffY = Math.round(staffTop + ruleIndex * staffGap);
            if (y - staffY < gap * .25f || y - staffY > gap * .49f) continue;
            int dx = 1;
            int leftLimit = Math.round(gap * 1.12f);
            while (dx <= leftLimit
                    && vertical(gray, width, height, owner, owner.x - dx, depth, 2) == 5) dx++;
            if (dx < Math.round(gap * .54f) || dx > leftLimit) continue;
            int outside = owner.x - dx - Math.round(gap * .18f);
            if (vertical(gray, width, height, owner, outside, depth, 2) > 1
                    || !ink(gray, width, height, outside, staffY)
                    || !ink(gray, width, height, outside - Math.round(gap * .55f), staffY))
                continue;
            int open = 0;
            for (int x = owner.x - dx + 1; x <= owner.x - Math.round(gap * .3f); x++) {
                if (!ink(gray, width, height, x, staffY - Math.round(gap * .24f))) open++;
            }
            if (open >= dx * .55f) return true;
        }
        return false;
    }

    private static boolean joinedBackwardHook(
            byte[] gray, int w, int h, List<Axis> owner, List<Axis> peer, float gap) {
        for (var a : owner)
            for (var b : peer) {
                int span = a.x - b.x;
                if (span < gap * 1.7f || span > gap * 4.2f) continue;
                float slope = (a.tip - b.tip) / (float) span;
                if (Math.abs(slope) <= .22f
                        && doubleRails(gray, w, h, a, b, slope, gap, false)
                        && boundedJoinedHook(gray, w, h, a, gap)) return true;
            }
        return false;
    }

    private static boolean boundedJoinedHook(byte[] gray, int w, int h, Axis owner, float gap) {
        int near = Math.round(gap * .28f), limit = Math.round(gap * .97f);
        for (int depth = Math.round(gap * 1.72f); depth <= Math.round(gap * 2.08f); depth++) {
            int shaftRow = owner.tip - depth;
            int dark = 0, clearance = 0;
            boolean connected = true;
            for (int dx = 1; dx < near; dx++)
                if (vertical(gray, w, h, owner, owner.x - dx, depth, 2) < 5) {
                    connected = false;
                    break;
                }
            if (!connected) continue;
            for (int dx = near; dx <= limit; dx++) {
                int x = owner.x - dx;
                if (vertical(gray, w, h, owner, x, depth, 2) < 5) break;
                dark++;
                if (!ink(gray, w, h, x, shaftRow - Math.round(gap * .24f))
                        && !ink(gray, w, h, x, shaftRow - Math.round(gap * .31f))) clearance++;
            }
            if (dark < Math.round(gap * .43f) || clearance * 2 < dark) continue;
            int beyond = owner.x - near - dark - Math.round(gap * .20f);
            if (vertical(gray, w, h, owner, beyond, depth, 2) <= 1) return true;
        }
        return false;
    }

    private static List<Axis> downwardAxes(PrintedBeamShafts.Shaft shaft) {
        return shaft.direction() == 1
                ? shaft.columns().stream().map(c -> new Axis(c.x(), c.endY(), 1)).toList()
                : List.of();
    }

    private static boolean isThinAttachedBand(
            byte[] gray, int w, int h, float x, float y, int shaft, float gap, float maxThickness) {
        int center = Math.round(x), row = Math.round(y);
        // A filled head at this spot extends farther away from the rail than a short hook.
        for (int col = center - Math.round(gap * .18f);
                col <= center + Math.round(gap * .18f);
                col++) {
            int top = row, bottom = row;
            while (top > row - gap && ink(gray, w, h, col, top - 1)) top--;
            while (bottom < row + gap && ink(gray, w, h, col, bottom + 1)) bottom++;
            if (bottom - top + 1 > gap * maxThickness) return false;
        }
        for (int col = center; col <= shaft; col++) {
            if (!ink(gray, w, h, col, row - 1)
                    || !ink(gray, w, h, col, row)
                    || !ink(gray, w, h, col, row + 1)) return false;
        }
        return true;
    }

    private static boolean proveSide(
            byte[] gray, int w, int h, List<Axis> owners, List<Axis> peers, float gap,int side) {
        return proveSide(gray, w, h, owners, peers, gap, side, false);
    }

    private static boolean proveSide(
            byte[] gray,
            int w,
            int h,
            List<Axis> owners,
            List<Axis> peers,
            float gap,
            int side,
            boolean allowFaintSeam) {
        for (var own : owners)
            for (var peer : peers) {
                float slope = (peer.tip-own.tip)/(float)(peer.x-own.x);
                if (own.direction != peer.direction
                        || (peer.x - own.x) * side < gap * 1.7f
                        || (peer.x - own.x) * side > gap * 4.2f
                        || Math.abs(slope) > .4f
                        || !doubleRails(gray, w, h, own, peer, slope, gap, allowFaintSeam))
                    continue;
                if (hook(gray,w,h,own,gap,side)) return true;
            }
        return false;
    }

    private static boolean doubleRails(
            byte[] gray,
            int w,
            int h,
            Axis own,
            Axis peer,
            float slope,
            float gap,
            boolean allowFaintSeam) {
        int margin = Math.max(2,Math.round(gap*.28f));
        int start=Math.min(own.x,peer.x)+margin,end=Math.max(own.x,peer.x)-margin;
        for (int a=Math.round(gap*.18f); a<=Math.round(gap*.4f); a++) {
            if (!rail(gray,w,h,own,slope,start,end,a)) continue;
            for (int b=Math.round(gap*.94f); b<=Math.round(gap*1.3f); b++) {
                if (!rail(gray,w,h,own,slope,start,end,b)) continue;
                int exposed=0,seam=(a+b)/2;
                for (int x=start; x<=end; x++)
                    if (!ink(gray,w,h,x,center(own,slope,x,seam))) exposed++;
                if (exposed >= (end - start + 1) * .28f
                        || allowFaintSeam && faintSeam(gray, w, h, own, slope, start, end, a, b))
                    return true;
            }
        }
        return false;
    }

    /** Both dark rail cores must border a lighter seam spread along the shaft interval. */
    private static boolean faintSeam(
            byte[] gray,
            int w,
            int h,
            Axis owner,
            float slope,
            int left,
            int right,
            int outer,
            int inner) {
        int samples = right - left + 1, count = 0;
        int[] thirds = new int[3];
        for (int x = left; x <= right; x++) {
            int outerY = center(owner, slope, x, outer), innerY = center(owner, slope, x, inner);
            if (Math.min(outerY, innerY) < 0 || Math.max(outerY, innerY) >= h || x < 0 || x >= w)
                return false;
            int core = Math.max(gray[outerY * w + x] & 255, gray[innerY * w + x] & 255);
            int light = 0;
            for (int depth = outer + 3; depth <= inner - 3; depth++)
                light = Math.max(light, gray[center(owner, slope, x, depth) * w + x] & 255);
            if (light >= core + 28) {
                thirds[(x - left) * 3 / samples]++;
                count++;
            }
        }
        return count >= samples * .48f && thirds[0] >= 2 && thirds[1] >= 2 && thirds[2] >= 2;
    }

    private static boolean rail(byte[] gray,int w,int h,Axis own,float slope,int a,int b,int depth) {
        int blanks=0;
        for (int x=a; x<=b; x++) {
            int center=center(own,slope,x,depth);
            boolean dark=true;
            for (int row=center-2; row<=center+2; row++) dark &= ink(gray,w,h,x,row);
            if (!dark && ++blanks > 1) return false;
        }
        return true;
    }

    private static int center(Axis own,float slope,int x,int depth) {
        return Math.round(own.tip+(x-own.x)*slope)-own.direction*depth;
    }

    static boolean ownsThirdRailAtUpStemEnd(
            byte[] gray, int width, int height, int[] stem, int[] previous, float gap) {
        var own = new Axis(stem[0], stem[1], -1);
        if (hook(gray, width, height, own, gap, -1) || hook(gray, width, height, own, gap, 1))
            return true;
        int margin = Math.round(gap * .33f);
        float slope = (stem[1] - previous[1]) / (float) (stem[0] - previous[0]);
        for (int depth = Math.round(gap * 1.62f); depth <= Math.round(gap * 2.18f); depth++) {
            if (rail(
                    gray, width, height, own, slope, previous[0] + margin, stem[0] - margin, depth))
                return true;
        }
        return false;
    }

    static boolean hasShortHook(byte[] gray, int width, int height,
            PrintedBeamShafts.Shaft shaft, float gap, int side) {
        for (int x = shaft.left(); x <= shaft.right(); x++)
            if (hook(gray, width, height, new Axis(x, shaft.endY(), shaft.direction()), gap, side))
                return true;
        return false;
    }

    private static boolean hook(byte[] gray,int w,int h,Axis own,float gap,int side) {
        int edge=Math.round(gap*.8f),near=Math.round(gap*.27f),radius=Math.round(gap*.36f);
        for (int d=Math.round(gap*1.65f); d<=Math.round(gap*2.24f); d++) {
            int bands=0, exposed=0;
            for (int dx=near; dx<=edge; dx++) {
                int x=own.x+side*dx;
                if (vertical(gray,w,h,own,x,d,radius)<gap*.44f
                        || vertical(gray,w,h,own,x,Math.round(gap*.32f),Math.round(gap*.43f))<gap*.35f
                        || vertical(gray,w,h,own,x,Math.round(gap*1.03f),Math.round(gap*.38f))<gap*.28f)
                    continue;
                bands++;
                if (!ink(gray,w,h,x,own.tip-own.direction*(d-Math.round(gap*.5f)))) exposed++;
            }
            if (bands<4 || exposed==0
                    || vertical(gray,w,h,own,own.x+side*(edge+Math.round(gap*.35f)),d,radius)>=gap*.31f)
                continue;
            if (vertical(gray,w,h,own,own.x+side,d,radius)>=gap*.55f) return true;
        }
        return false;
    }

    private static int vertical(byte[] gray,int w,int h,Axis shaft,int x,int d,int radius) {
        int count=0;
        for (int j=d-radius; j<=d+radius; j++)
            if (ink(gray,w,h,x,shaft.tip-shaft.direction*j)) count++;
        return count;
    }

    private static boolean ink(byte[] gray,int w,int h,int x,int y) {
        return x>=0 && x<w && y>=0 && y<h && (gray[y*w+x]&255)<155;
    }
}
