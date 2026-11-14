// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Local direction ownership requires a written head and all five printed rails. */
final class PrintedDirectionStaff {
    private PrintedDirectionStaff() {}

    static PlayingTechniqueDetector.Staff at(
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            float x,
            float top,
            float bottom) {
        return choose(staffs, measures, notes, gray, width, height, x, top, bottom, true);
    }

    static PlayingTechniqueDetector.Staff local(
            PlayingTechniqueDetector.Staff staff,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            float x) {
        return choose(List.of(staff), measures, notes, gray, width, height, x, 0, 0, false);
    }

    private static PlayingTechniqueDetector.Staff choose(
            List<PlayingTechniqueDetector.Staff> staffs,
            List<MeasureRegion> measures,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            float x,
            float top,
            float bottom,
            boolean requireOwner) {
        if (gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || !Float.isFinite(x)
                || x < 0
                || x >= width
                || requireOwner
                        && (!Float.isFinite(top)
                                || !Float.isFinite(bottom)
                                || top < 0
                                || bottom >= height
                                || bottom < top)) return null;
        PlayingTechniqueDetector.Staff best = null;
        float bestDistance = Float.MAX_VALUE;
        for (var staff : staffs) {
            float gap = staff.gap();
            if (!Float.isFinite(gap)
                    || gap < 6
                    || gap > Math.min(width, height) / 8f
                    || !Float.isFinite(staff.top())
                    || !Float.isFinite(staff.bottom())
                    || staff.top() >= staff.bottom()
                    || staff.index() < 0
                    || staff.count() <= staff.index()) continue;
            float center = (staff.top() + staff.bottom()) * .5f / height;
            ScoreNoteEvent witness = null;
            float nearest = Float.MAX_VALUE, hint = 0;
            for (var note : notes) {
                if (note.staffIndex() != staff.index()
                        || note.staffCount() != staff.count()
                        || note.crossStaffBeam()
                        || note.measureIndex() < 0
                        || note.measureIndex() >= measures.size()
                        || !Float.isFinite(note.pageY())
                        || note.pageY() <= 0) continue;
                var region = measures.get(note.measureIndex());
                if (center < region.top() - gap / height || center > region.bottom() + gap / height)
                    continue;
                if (x < region.left() * width - gap * 3 || x > region.right() * width + gap * 3)
                    continue;
                float nx =
                        (region.left()
                                        + note.positionInMeasure()
                                                * (region.right() - region.left()))
                                * width;
                float floor = note.pageY() * height + note.staffStep() * gap * .5f;
                if (!Float.isFinite(floor) || Math.abs(floor - staff.bottom()) > gap * 3) continue;
                float distance = Math.abs(x - nx);
                if (distance < nearest && distance <= gap * 16) {
                    nearest = distance;
                    witness = note;
                    hint = floor;
                }
            }
            if (witness == null) continue;
            var rules = printedRules(gray, width, height, x, hint, gap);
            if (rules == null && requireOwner) {
                var terminal = terminalRegion(measures, witness, staff, width, height, x);
                if (terminal != null)
                    rules =
                            TerminalDirectionRails.read(
                                    gray,
                                    width,
                                    height,
                                    x,
                                    hint,
                                    gap,
                                    terminal.left(),
                                    terminal.right());
            }
            if (rules == null
                    || rules.length < 2
                    || !Float.isFinite(rules[0])
                    || !Float.isFinite(rules[1])
                    || Math.abs(rules[0] - hint) > gap * 1.5f
                    || Math.abs(rules[1] - gap) > gap * .15f) continue;
            var local =
                    new PlayingTechniqueDetector.Staff(
                            rules[0] - rules[1] * 4,
                            rules[0],
                            rules[1],
                            staff.index(),
                            staff.count());
            var owner =
                    requireOwner
                            ? ScoreDynamicsDetector.directionOwner(
                                    List.of(local), top, bottom, notes, measures, height)
                            : local;
            // Vertical clearance is measured from ink edges; the ranking penalty below
            // must not consume the permitted gap. Complete rails and a head remain mandatory.
            if (owner == null
                    && requireOwner
                    && bottom <= local.top() - local.gap() * .25f
                    && (local.top() - bottom) / local.gap() <= 4.5f) owner = local;
            if (owner == null
                    && requireOwner
                    && bottom <= local.top() - local.gap() * .25f
                    && witness.pageY() * height < local.top()) {
                float ceiling = witness.pageY() * height - local.gap() * 1.5f;
                float above = (ceiling - bottom) / local.gap() + .75f;
                if (top >= 0 && above >= 0 && above <= 4.5f) owner = local;
            }
            if (owner == null) continue;
            float distance =
                    requireOwner
                            ? (top >= local.bottom()
                                    ? (top - local.bottom()) / local.gap()
                                    : (local.top() - bottom) / local.gap() + .75f)
                            : nearest / gap;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = local;
            }
        }
        return best;
    }

    /** A finite last written measure bounds the fallback; ordinary rail APIs stay bilateral. */
    private static MeasureRegion terminalRegion(
            List<MeasureRegion> measures,
            ScoreNoteEvent witness,
            PlayingTechniqueDetector.Staff staff,
            int width,
            int height,
            float x) {
        float center = (staff.top() + staff.bottom()) * .5f / height;
        float margin = staff.gap() / height;
        int selected = -1;
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            if (!Float.isFinite(m.left())
                    || !Float.isFinite(m.right())
                    || !Float.isFinite(m.top())
                    || !Float.isFinite(m.bottom())
                    || m.left() < 0
                    || m.right() > 1
                    || m.left() >= m.right()
                    || m.top() < 0
                    || m.bottom() > 1
                    || m.top() >= m.bottom()) return null;
            if (center < m.top() - margin || center > m.bottom() + margin) continue;
            if (x >= m.left() * width && x <= m.right() * width) {
                if (selected >= 0) return null;
                selected = i;
            }
        }
        if (selected < 0 || witness.measureIndex() != selected) return null;
        var last = measures.get(selected);
        for (int i = 0; i < measures.size(); i++) {
            var m = measures.get(i);
            if (i != selected
                    && center >= m.top() - margin
                    && center <= m.bottom() + margin
                    && m.right() > last.right()) return null;
        }
        int radius = Math.round(staff.gap() * 3.5f);
        if (last.right() * width - x > radius) return null;
        return last;
    }

    static float[] printedRules(
            byte[] gray, int width, int height, float x, float hint, float gap) {
        if (!FaintDirectionRails.valid(gray, width, height, x, hint, gap)) return null;
        var strict = strictRules(gray, width, height, x, hint, gap);
        if (strict != null) return strict;
        var narrow = FaintDirectionRails.read(gray, width, height, x, hint, gap, 3.5f);
        var wide = FaintDirectionRails.read(gray, width, height, x, hint, gap, 5.5f);
        if (narrow != null
                && wide != null
                && (Math.abs(narrow[0] - wide[0]) > gap * .2f
                        || Math.abs(narrow[1] - wide[1]) > gap * .08f)) return null;
        return narrow != null ? narrow : wide;
    }

    private static final float[] STRICT_SLOPES = {0, .04f, -.04f, .08f, -.08f, .12f, -.12f, .16f, -.16f};

    private static float[] strictRules(
            byte[] gray, int width, int height, float x, float hint, float gap) {
        int radius = Math.round(gap * 3.5f), center = Math.round(x);
        int left = center - radius, right = center + radius;
        int top = (int) Math.floor(hint - gap * 5.5f), bottom = (int) Math.ceil(hint + gap * 1.5f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return null;
        int w = right - left + 1,
                h = bottom - top + 1,
                exclusion = Math.max(2, Math.round(gap * .5f));
        float[] accepted = null;
        byte[] crop = new byte[w * h];
        int[] offsets = new int[w];
        int[] rowCounts = new int[h];
        float[] shifts = {0, -gap * .2f, gap * .2f};
        int flank = Math.max(2, Math.round(gap * .3f));
        int band = Math.max(1, Math.round(gap * .2f));
        int flankStride = flank * w;
        for (float slope : STRICT_SLOPES) {
            boolean valid = true;
            if (slope == 0) {
                for (int row = 0; row < h; row++)
                    System.arraycopy(gray, (top + row) * width + left, crop, row * w, w);
            } else {
                for (int xx = left; xx <= right; xx++) {
                    int dy = Math.round((xx - x) * slope);
                    if (top + dy < 0 || bottom + dy >= height) {
                        valid = false;
                        break;
                    }
                    offsets[xx - left] = (top + dy) * width + xx;
                }
                if (valid) {
                    for (int row = 0, dest = 0; row < h; row++, dest += w) {
                        int sourceOffset = row * width;
                        for (int col = 0; col < w; col++)
                            crop[dest + col] = gray[offsets[col] + sourceOffset];
                    }
                }
            }
            if (!valid) continue;
            // Written-head floors inherit a small staff-gap rounding error at remote steps.
            // Every allowed reference offset must still converge on the same complete raw rails.
            java.util.Arrays.fill(rowCounts, 0);
            for (float shift : shifts) {
                var found =
                        StaffPitchTrack.broadStraightPitch(
                                crop, w, h, hint - top + shift, gap, false, rowCounts);
                if (found == null) continue;
                boolean bilateral = true;
                int samples = radius - exclusion;
                for (int line = 0; line < 5 && bilateral; line++) {
                    int centerY = Math.round(found[0] - line * found[1]);
                    int from = Math.max(flank, centerY - band) * w;
                    int to = Math.min(h - 1 - flank, centerY + band) * w;
                    for (int side = -1; side <= 1; side += 2) {
                        int hits = 0;
                        for (int dx = exclusion + 1; dx <= radius; dx++) {
                            int column = center + side * dx - left;
                            for (int at = from + column; at <= to + column; at += w) {
                                int tone = crop[at] & 255;
                                if (tone < 170
                                        && (crop[at - flankStride] & 255) > tone + 20
                                        && (crop[at + flankStride] & 255) > tone + 20) {
                                    hits++;
                                    break;
                                }
                            }
                        }
                        if (samples < 8 || hits < samples * .6f) {
                            bilateral = false;
                            break;
                        }
                    }
                }
                if (!bilateral) continue;
                found[0] += top;
                if (accepted != null
                        && (Math.abs(accepted[0] - found[0]) > gap * .2f
                                || Math.abs(accepted[1] - found[1]) > gap * .08f)) return null;
                accepted = found;
            }
        }
        return accepted;
    }
}
