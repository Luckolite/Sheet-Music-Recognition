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
        if (column == null || column.gap() < 8) return false;
        var a = column.first.note;
        var b = column.second.note;
        if (a.staffCount() != 1
                || a.beamCount() != b.beamCount() || a.beamCount() < 2 || a.beamCount() > 3
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
        for (var owner : downwardAxes(owners.get(0))) {
            if (!hook(gray, width, height, owner, gap, -1)) continue;
            for (var peer : downwardAxes(peers.get(0))) {
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
        for (var a : downwardAxes(own.get(0)))
            for (var b : downwardAxes(peer.get(0))) {
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
