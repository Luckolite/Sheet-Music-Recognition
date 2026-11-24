// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Verify a reversed stem reading at the beam of a closed high-ledger bar. */
final class LedgerBeamTerminalAlias {
    private LedgerBeamTerminalAlias() {}

    static boolean proves(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> notes, List<Integer> owners, ScoreNoteEvent alias) {
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        var staff = WrappedMeasureBoundaries.verifiedStaffPockets(ink, width, height, bar, false);
        if (staff == null) return false;
        float aliasX = x(left, right, alias), aliasY = alias.pageY() * height;
        var budget = new PrintedBeamShafts.Budget(240_000);
        var reverse = PrintedBeamShafts.at(ink, width, height, aliasX, aliasY, gap, budget);
        if (reverse.size() != 1 || reverse.get(0).direction() != -1) return false;
        var fromTip = reverse.get(0);
        var shafts = new ArrayList<PrintedBeamShafts.Shaft>(owners.size());
        float first = x(left, right, notes.get(owners.get(0)));
        float last = x(left, right, notes.get(owners.get(owners.size() - 1)));
        if (!WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                ink, width, height, bar, first, last, staff)) return false;
        int terminalMatches = 0;
        float previous = Float.NaN;
        for (int owner : owners) {
            var head = notes.get(owner);
            float x = x(left, right, head), y = head.pageY() * height;
            var frame = staff.at(x);
            if (Math.abs(y - (frame[0] - head.staffStep() * frame[1] * .5f)) > gap * .40f
                    || (x - previous < gap * 1.8f || x - previous > gap * 3.7f))
                return false;
            previous = x;
            var local = PrintedBeamShafts.at(ink, width, height, x, y, gap, budget);
            if (local.size() != 1 || local.get(0).direction() != 1 || budget.refused()) return false;
            var shaft = local.get(0);
            shafts.add(shaft);
            if (x - aliasX < gap * .68f || x - aliasX > gap * 1.24f
                    || aliasY - y < gap * 4.1f || aliasY - y > gap * 7.6f) continue;
            if (reciprocalColumns(fromTip, shaft, aliasY, y, gap)) terminalMatches++;
        }
        if (terminalMatches != 1) return false;
        for (int i = 0; i + 1 < shafts.size(); i++)
            if (!rail(ink, width, height, shafts.get(i), shafts.get(i + 1), gap, i % 3 != 2))
                return false;
        return true;
    }

    static boolean reciprocalColumns(PrintedBeamShafts.Shaft reverse,
            PrintedBeamShafts.Shaft forward, float tipY, float headY, float gap) {
        for (var a : reverse.columns()) {
            if (Math.abs(a.endY() - headY) > gap * .32f) continue;
            for (var b : forward.columns())
                if (a.x() == b.x() && Math.abs(b.endY() - tipY) <= gap * .46f) return true;
        }
        return false;
    }

    private static boolean rail(byte[] ink, int width, int height, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b, float gap, boolean expected) {
        int left = (a.left() + a.right()) / 2, right = (b.left() + b.right()) / 2;
        int margin = Math.round(gap * .50f), minimum = (int) Math.ceil(gap * .42f);
        int maxThickness = Math.round(gap * .89f);
        int matching = 0, count = right - left + 1 - margin * 2;
        if (count < gap * .75f || left < 0 || right >= width) return false;
        for (int x = left + margin; x <= right - margin; x++) {
            float tip = a.endY() + (b.endY() - a.endY()) * (x - left) / (float) (right - left);
            int top = Math.round(tip - gap * 1.85f), bottom = Math.round(tip + gap * .26f);
            if (top < 0 || bottom >= height) return false;
            int run = 0, thick = 0;
            boolean nearTip = false;
            for (int y = top; y <= bottom + 1; y++) {
                if (y <= bottom && (ink[y * width + x] & 255) < 155) {
                    run++;
                } else {
                    if (run >= minimum) {
                        if (run > maxThickness || y - run == top || y > bottom) return false;
                        thick++;
                        nearTip = Math.abs(y - 1 - tip) <= gap * .30f;
                    }
                    run = 0;
                }
            }
            if (expected ? thick == 1 && nearTip : thick == 0) matching++;
        }
        return matching >= Math.ceil(count * .92f);
    }

    private static float x(float left, float right, ScoreNoteEvent note) {
        return left + (right - left) * note.positionInMeasure();
    }
}
