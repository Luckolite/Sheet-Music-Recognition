// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Recognizes short owned tertiary hooks before a separate dotted-half cadence. */
final class DottedCadenceShortBeams {
    private DottedCadenceShortBeams() {}

    /** Use final note rhythm after stem and tie validation and before clock/beam capture. */
    static List<ScoreNoteEvent> apply(
            List<ScoreNoteEvent> notes, List<MeasureRegion> measures,
            List<PlayingTechniqueDetector.Staff> staffs, byte[] gray, int width, int height) {
        if (gray == null || staffs.isEmpty() || measures.size() < 3 || notes.size() < 13)
            return notes;
        var heads = new ArrayList<OwnedTertiaryBeamlets.Head>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            var region = measures.get(note.measureIndex());
            float y = note.pageY() * height;
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
            float center = (region.top() + region.bottom()) * height * .5f;
            float gap = 0, nearest = Float.POSITIVE_INFINITY;
            for (var staff : staffs) {
                if (staff.index() != note.staffIndex() || staff.count() != note.staffCount()) continue;
                float distance = Math.abs(center - (staff.top() + staff.bottom()) * .5f);
                if (distance < nearest && distance < staff.gap() * 2) {
                    nearest = distance;
                    gap = staff.gap();
                }
            }
            heads.add(new OwnedTertiaryBeamlets.Head(i, note, x, y, gap));
        }
        var corrected = recover(gray, width, height, heads, measures);
        if (corrected.isEmpty()) return notes;
        var result = new ArrayList<>(notes);
        for (int i : corrected) {
            var n = notes.get(i);
            result.set(i, new ScoreNoteEvent(
                    n.measureIndex(), n.positionInMeasure(), n.staffStep(), n.staffIndex(), n.staffCount(),
                    n.pageY(), n.tiedFromPrevious(), n.augmentationDots(), 3, n.writtenAccidental(),
                    n.unbeamedDurationBeats(), n.tupletDivisor(), n.followingRestBeats(), n.articulations(),
                    n.clefBottomDiatonic(), n.crossStaffBeam(), n.leadingRestBeats(), n.compactOpening(),
                    n.octaveShift(), n.boundaryTies(), n.tupletNormalNotes(), n.stemDirection(), n.kind(),
                    n.visualSize(), n.harmonicSemitones(), n.openStringTouch()));
        }
        return List.copyOf(result);
    }

    private record Axis(int x, int tip) {}
    private record Owner(OwnedTertiaryBeamlets.Head head, List<Axis> axes) {}

    static List<Integer> recover(
            byte[] gray, int width, int height, List<OwnedTertiaryBeamlets.Head> heads,
            List<MeasureRegion> measures) {
        if (gray == null || gray.length != (long) width * height || measures.size() < 3
                || heads.size() < 13) return List.of();
        var groups = new ArrayList<List<OwnedTertiaryBeamlets.Head>>(measures.size());
        for (int i = 0; i < measures.size(); i++) groups.add(new ArrayList<>());
        for (var head : heads) groups.get(head.note().measureIndex()).add(head);
        var result = new ArrayList<Integer>();
        var budget = new PrintedBeamShafts.Budget(1_000_000);
        for (int i = 1; i + 1 < measures.size(); i++) {
            var run = groups.get(i - 1);
            var cadence = groups.get(i);
            if (run.size() < 12 || run.size() > 14 || cadence.size() != 1) continue;
            var a = measures.get(i - 1);
            var b = measures.get(i);
            var c = measures.get(i + 1);
            float gap = cadenceGap(gray, width, height, a, b, c, cadence.get(0));
            if (gap <= 0) continue;
            result.addAll(ownedRun(gray, width, height, run, gap, budget));
        }
        return List.copyOf(result);
    }

    private static float cadenceGap(
            byte[] gray, int width, int height, MeasureRegion before, MeasureRegion bar,
            MeasureRegion after, OwnedTertiaryBeamlets.Head head) {
        var n = head.note();
        float gap = head.gap();
        float left = bar.left() * width, right = bar.right() * width;
        float seam = (left + before.right() * width) * .5f;
        if (gap < 8 || gap > 32 || !plainNote(n) || n.unbeamedDurationBeats() != 2
                || n.augmentationDots() != 1 || n.beamCount() != 0
                || right - left < gap * 3.5f || right - left > gap * 7.5f
                || left - before.right() * width < gap * .55f
                || left - before.right() * width > gap * 1.5f
                || Math.abs(bar.top() - before.top()) * height > gap * .35f
                || after.top() * height <= bar.bottom() * height + gap * 2
                || head.x() - seam < gap * 1.4f || head.x() - seam > gap * 3
                || right - head.x() < gap * 2.5f) return 0;
        float hint = bar.bottom() * height - gap * 2.1f;
        var rails = PrintedDirectionStaff.printedRules(gray, width, height, right - gap * 2.5f, hint, gap);
        if (rails == null) return 0;
        gap = rails[1];
        var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * gap, rails[0], gap, 0, 1);
        if (staff.top() < 1 || staff.bottom() >= height - 1
                || !OmrMeasurePostProcessor.verticalRuleAt(gray, width, Math.round(seam),
                        Math.round(staff.top()), Math.round(staff.bottom()), gap)
                || PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true) < 0)
            return 0;
        return gap;
    }

    private static List<Integer> ownedRun(
            byte[] gray, int width, int height, List<OwnedTertiaryBeamlets.Head> run, float gap,
            PrintedBeamShafts.Budget budget) {
        var owners = new ArrayList<Owner>();
        var unowned = new ArrayList<OwnedTertiaryBeamlets.Head>();
        for (var head : run) {
            var n = head.note();
            var shafts = PrintedBeamShafts.at(gray, width, height, head.x(), head.y(), gap, budget);
            if (budget.refused()) return List.of();
            var axes = new ArrayList<Axis>();
            if (shafts.size() > 1) return List.of();
            if (shafts.size() == 1 && shafts.get(0).direction() > 0) {
                var shaft = shafts.get(0);
                for (var column : shaft.columns()) {
                    if (shaft.endY() - column.endY() <= 2)
                        axes.add(new Axis(column.x(), shaft.endY()));
                }
            } else if (shafts.isEmpty() && n.stemDirection() == -1) {
                axes.addAll(downShaftAtEdge(gray, width, height, head, gap));
            }
            if (axes.isEmpty()) {
                unowned.add(head);
                continue;
            }
            if (!plainNote(n) || n.unbeamedDurationBeats() != 0 || n.stemDirection() > 0
                    || n.beamCount() < 2 || n.beamCount() > 3) return List.of();
            owners.add(new Owner(head, axes));
        }
        if (owners.size() != 12) return List.of();
        owners.sort(Comparator.comparingDouble(owner -> owner.head.x()));
        var result = new ArrayList<Integer>();
        int alreadyThree = 0;
        for (int i = 0; i < owners.size(); i += 2) {
            var dotted = owners.get(i);
            var shortNote = owners.get(i + 1);
            if (dotted.head.note().augmentationDots() != 1 || dotted.head.note().beamCount() != 2
                    || shortNote.head.note().augmentationDots() != 0
                    || hasHook(gray, width, dotted.axes, gap)
                    || !ownedPair(gray, width, dotted.axes, shortNote.axes, gap)) return List.of();
            if (shortNote.head.note().beamCount() == 3) alreadyThree++;
            else result.add(shortNote.head.index());
        }
        for (var head : unowned) {
            // Clock repair leaves these components alone; a real independent higher or lower
            // voice cannot be separated by this single-staff cadence rule.
            boolean byBeams = owners.stream().anyMatch(owner ->
                    Math.abs(owner.head.x() - head.x()) <= gap * 1.15f
                    && owner.axes.stream().anyMatch(axis ->
                            head.y() >= axis.tip - gap * 2.5f && head.y() <= axis.tip + gap * .6f));
            if (!byBeams) return List.of();
        }
        return alreadyThree >= 2 ? result : List.of();
    }

    /** A one-row dropout at a stem tip can split an otherwise identical set of columns. */
    private static List<Axis> downShaftAtEdge(
            byte[] gray, int width, int height, OwnedTertiaryBeamlets.Head head, float gap) {
        int left = Math.max(0, Math.round(head.x() - gap * .8f));
        int right = Math.min(width - 1, Math.round(head.x() - gap * .4f));
        int start = Math.round(head.y()) + Math.round(gap * .2f);
        int end = Math.min(height, start + Math.round(gap * 7));
        var axes = new ArrayList<Axis>();
        int previous = 0, tip = 0;
        for (int x = left; x <= right; x++) {
            int last = start - 1, misses = 0;
            for (int y = start; y < end; y++) {
                if (inkCount(gray, width, x, y, 0) > 0) last = y;
                else if (y - last >= 2 || ++misses > 1) break;
            }
            if (last - start < gap * 1.7f || last >= end - 1) continue;
            if (!axes.isEmpty() && (x != previous + 1 || Math.abs(tip - last) > gap * .15f))
                return List.of();
            axes.add(new Axis(x, last));
            previous = x;
            tip = last;
        }
        return axes.size() >= 2 && axes.size() <= Math.round(gap * .3f) ? axes : List.of();
    }

    private static boolean plainNote(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED && n.staffIndex() == 0 && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && !n.crossStaffBeam()
                && !n.tiedFromPrevious() && n.boundaryTies() == 0 && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0 && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean ownedPair(
            byte[] gray, int width, List<Axis> left, List<Axis> right, float gap) {
        for (var shortAxis : right) {
            if (!hook(gray, width, shortAxis, gap)) continue;
            for (var longAxis : left) {
                int span = shortAxis.x - longAxis.x;
                if (span < gap * 1.7f || span > gap * 4.2f
                        || Math.abs(shortAxis.tip - longAxis.tip) > span * .3f) continue;
                if (band(gray, width, longAxis, shortAxis, gap, .19f, .57f)
                        && band(gray, width, longAxis, shortAxis, gap, .94f, 1.32f)) return true;
            }
        }
        return false;
    }

    private static boolean band(
            byte[] gray, int width, Axis a, Axis b, float gap, float near, float far) {
        int margin = Math.round(gap * .3f), halfHeight = Math.round(gap * .125f);
        for (int depth = Math.round(gap * near); depth <= Math.round(gap * far); depth++) {
            boolean continuous = true;
            for (int x = a.x + margin; x <= b.x - margin; x++) {
                int center = Math.round(a.tip + (b.tip - a.tip) * (x - a.x) / (float) (b.x - a.x));
                if (inkCount(gray, width, x, center - depth, halfHeight) != 2 * halfHeight + 1) {
                    continuous = false;
                    break;
                }
            }
            if (continuous) return true;
        }
        return false;
    }

    private static boolean hasHook(byte[] gray, int width, List<Axis> axes, float gap) {
        return axes.stream().anyMatch(axis -> hook(gray, width, axis, gap));
    }

    private static boolean hook(byte[] gray, int width, Axis axis, float gap) {
        int x = axis.x, tip = axis.tip;
        int halfHeight = Math.round(gap * .19f);
        int length = Math.round(gap * .6875f);
        int farLeft = Math.round(gap * 1.4f), nearLeft = Math.round(gap * 1.14f);
        int nearRight = Math.round(gap * .19f), farRight = Math.round(gap * .52f);
        if (x - farLeft < 0 || x + farRight >= width) return false;
        for (int y = tip - Math.round(gap * 2.5f); y <= tip - Math.round(gap * 1.5f); y++) {
            if (y - halfHeight < 0 || y + halfHeight >= gray.length / width) continue;
            boolean attached = true;
            for (int dx = -length; dx <= 0; dx++) {
                if (inkCount(gray, width, x + dx, y, halfHeight) < (2 * halfHeight + 1) * .8f) {
                    attached = false;
                    break;
                }
            }
            if (!attached) continue;
            boolean isolated = true;
            for (int dx = -farLeft; dx <= farRight; dx++) {
                if (dx > -nearLeft && dx < nearRight) continue;
                if (inkCount(gray, width, x + dx, y, halfHeight) > (2 * halfHeight + 1) * .6f) {
                    isolated = false;
                    break;
                }
            }
            if (isolated) return true;
        }
        return false;
    }

    private static int inkCount(byte[] gray, int width, int x, int y, int halfHeight) {
        if (y - halfHeight < 0 || y + halfHeight >= gray.length / width) return 0;
        int dark = 0;
        for (int row = y - halfHeight; row <= y + halfHeight; row++)
            if ((gray[row * width + x] & 255) < 155) dark++;
        return dark;
    }
}
