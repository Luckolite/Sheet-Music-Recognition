// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.BitSet;
import java.util.List;

/** A lower fingering 1 can supply the apparent dot of the preceding sixteenth. */
final class FingeringOneAugmentation {
    private FingeringOneAugmentation() {}

    static int borrowedDot(byte[] gray, byte[] raw, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> row) {
        var closing = row.get(row.size() - 1);
        if (!ClosedMonophonicSixteenths.cadenceHalf(closing) || closing.stemDirection() != 1) return -1;
        var sixteenths = row.subList(0, row.size() - 1);
        int owner = -1;
        for (int i = 0; i < sixteenths.size(); i++) {
            var note = sixteenths.get(i);
            if (!ClosedMonophonicSixteenths.regularQuartetHead(note) || note.stemDirection() != 1
                    || note.tupletDivisor() != 1 || note.tiedFromPrevious()) return -1;
            if (note.augmentationDots() == 0) continue;
            if (note.augmentationDots() != 1 || owner >= 0 || i % 4 == 3) return -1;
            owner = i;
        }
        if (owner < 0) return -1;
        return proves(gray, width, height, bar, sixteenths, closing, owner)
                && proves(raw, width, height, bar, sixteenths, closing, owner) ? owner : -1;
    }

    private static boolean proves(byte[] ink, int width, int height, MeasureRegion bar,
            List<ScoreNoteEvent> sixteenths, ScoreNoteEvent closing, int owner) {
        float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
        if (nominal < 10 || nominal > 25) return false;
        var before = sixteenths.get(owner);
        var after = sixteenths.get(owner + 1);
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float priorX = left + before.positionInMeasure() * span;
        float nextX = left + after.positionInMeasure() * span;
        float priorY = before.pageY() * height, nextY = after.pageY() * height;
        if (nextX - priorX < 1.9f * nominal || nextX - priorX > 2.65f * nominal
                || priorY - nextY < .8f * nominal || priorY - nextY > 1.2f * nominal) return false;
        float floor = bar.bottom() * height - 2.1f * nominal;
        var track = StaffPitchTrack.forPrintedSlurs(ink, width, height, floor - 4 * nominal, floor, nominal);
        var fit = track == null ? null : track.at(nextX);
        return fit != null && provesDigitAndClearance(ink, width, height, priorX, priorY, nextX, nextY, fit[0], fit[1])
                && ClosedMonophonicSixteenths.provesRegularQuartets(ink, width, height, bar, sixteenths)
                && ClosedMonophonicSixteenths.isolatedHalf(ink, width, height, bar, sixteenths.get(sixteenths.size() - 1), closing);
    }

    static boolean provesDigitAndClearance(byte[] ink, int width, int height,
            float priorX, float priorY, float nextX, float nextY, float floor, float gap) {
        if (floor - nextY < 1.3f * gap || floor - nextY > 1.7f * gap) return false;
        int left = Math.round(nextX - .62f * gap), right = Math.round(nextX + .57f * gap);
        int top = Math.round(floor - .74f * gap), bottom = Math.round(floor + .76f * gap);
        int flank = Math.round(.55f * gap);
        int apertureLeft = Math.round(priorX + .90f * gap);
        int apertureTop = Math.round(priorY - .58f * gap), apertureBottom = Math.round(priorY + .45f * gap);
        if (Math.min(left, apertureLeft) < flank || right + flank >= width
                || Math.min(top, apertureTop) < 0 || Math.max(bottom, apertureBottom) >= height) return false;

        int localWidth = right - left + 1, localHeight = bottom - top + 1;
        var crop = new byte[localWidth * localHeight];
        var rule = new BitSet(crop.length);
        int l = localWidth, r = -1, t = localHeight, b = -1;
        for (int y = 0; y < localHeight; y++) {
            int row = y + top;
            System.arraycopy(ink, row * width + left, crop, y * localWidth, localWidth);
            if (Math.abs(row - floor) < .24f * gap && staffInk(ink, width, left, right, row, flank)) {
                rule.set(y * localWidth, (y + 1) * localWidth);
                continue;
            }
            for (int x = 0; x < localWidth; x++) {
                if ((crop[y * localWidth + x] & 255) > 165) continue;
                l = Math.min(l, x);
                r = Math.max(r, x);
                t = Math.min(t, y);
                b = Math.max(b, y);
            }
        }
        if (b - t + 1 < gap || b - t + 1 > 1.4f * gap
                || Math.abs(left + (l + r) * .5f - nextX) > .27f * gap
                || Math.abs(top + t - priorY) > .36f * gap
                || top + b - floor < .39f * gap) return false;
        var digit = JoinedDigitTemplates.inspect(crop, localWidth, localHeight, rule, l, t, r, b, () -> {});
        if (digit == null || digit.digit() != 1 || digit.best() < .70f || digit.best() - digit.runner() < .13f)
            return false;

        // Preserve a separate real dot, even if it touches a staff rule or is close to the digit.
        int apertureRight = left + l - 2;
        if (apertureRight - apertureLeft < .42f * gap) return false;
        for (int y = apertureTop; y <= apertureBottom; y++) {
            double step = (floor - y) / gap;
            if (Math.abs(step - Math.round(step)) < .23f
                    && staffInk(ink, width, apertureLeft, apertureRight, y, flank)) continue;
            int run = 0;
            for (int x = apertureLeft; x <= apertureRight; x++) {
                run = (ink[y * width + x] & 255) <= 165 ? run + 1 : 0;
                if (run >= .18f * gap) return false;
            }
        }
        return true;
    }

    private static boolean staffInk(byte[] ink, int width, int left, int right, int row, int flank) {
        int l = 0, r = 0;
        for (int i = 1; i <= flank; i++) {
            if ((ink[row * width + left - i] & 255) < 165) l++;
            if ((ink[row * width + right + i] & 255) < 165) r++;
        }
        return Math.max(l, r) >= .72f * flank;
    }
}
