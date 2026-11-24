// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.List;

/** Measures ruled flanks around the beam occlusion of a terminal below-staff slur. */
final class TerminalBelowSlurRails {
    private TerminalBelowSlurRails() {}

    static boolean proves(
            byte[] gray, int width, int height, StaffPitchTrack track,
            PrintedSlurOwners.Location a, PrintedSlurOwners.Location b,
            ScoreNoteEvent first, ScoreNoteEvent second, List<MeasureRegion> measures) {
        if (track.verifiedAt(a.x()) && track.verifiedAt(b.x())) return true;
        int measureIndex = first.measureIndex();
        if (measureIndex < 0 || measureIndex >= measures.size()) return false;
        var measure = measures.get(measureIndex);
        float left = measure.left() * width, right = measure.right() * width;
        float[] around = track.at(b.x());
        float floor = around[0], gap = around[1];
        if (b.x() >= right || right - b.x() > gap * 3.5f || a.x() < left
                || measure.top() * height > floor - gap * 4f
                || measure.bottom() * height < floor) return false;
        for (var other : measures) {
            if (other.right() <= measure.right()) continue;
            if (other.top() * height <= floor - gap * 4f && other.bottom() * height >= floor)
                return false;
        }
        var seed = track.at(a.x());
        float[] entry = PrintedDirectionStaff.printedRules(gray, width, height, a.x(), seed[0], seed[1]);
        if (entry == null || Math.abs(entry[0] - floor) > gap * .14f
                || Math.abs(entry[1] - gap) > gap * .045f
                || !exactHead(a, first, entry[0], entry[1])
                || !exactHead(b, second, floor, gap)) return false;

        int x = Math.round(b.x());
        int start = Math.max((int) Math.ceil(left), x - Math.round(gap * 4.45f));
        int end = Math.min((int) Math.floor(right - gap * .20f), x + Math.round(gap * 2.3f));
        int near = thinFlank(gray, width, height, floor, gap, start, x - Math.round(gap * .68f));
        int far = thinFlank(gray, width, height, floor, gap, x + Math.round(gap * .64f), end);
        if (near < 0 || far <= near) return false;
        for (int rule = 0; rule < 5; rule++) {
            int y = Math.round(floor - rule * gap);
            int contrasting = 0;
            for (int column = near; column <= far; column++)
                if (hasRuleInk(gray, width, column, y)) contrasting++;
            if (contrasting * 10 < (far - near + 1) * 9 || !hasRuleInk(gray, width, x, y))
                return false;
        }
        return true;
    }

    private static boolean exactHead(
            PrintedSlurOwners.Location head, ScoreNoteEvent note, float floor, float gap) {
        return Math.abs(head.y() - (floor - note.staffStep() * gap * .5f)) <= gap * .24f;
    }

    private static int thinFlank(
            byte[] gray, int width, int height, float floor, float gap, int left, int right) {
        int continuous = 0, required = Math.max(6, Math.round(gap * .46f));
        for (int x = left; x <= right; x++) {
            boolean thin = true;
            for (int rule = 0; rule < 5 && thin; rule++) {
                int y = Math.round(floor - rule * gap);
                thin = x >= 0 && x < width && y >= 4 && y < height - 4
                        && hasRuleInk(gray, width, x, y)
                        && (gray[(y - 4) * width + x] & 255) > 205
                        && (gray[(y + 4) * width + x] & 255) > 205;
            }
            continuous = thin ? continuous + 1 : 0;
            if (continuous >= required) return x - required / 2;
        }
        return -1;
    }

    private static boolean hasRuleInk(byte[] gray, int width, int x, int y) {
        for (int offset = -2; offset <= 2; offset++)
            if ((gray[(y + offset) * width + x] & 255) < 155) return true;
        return false;
    }
}
