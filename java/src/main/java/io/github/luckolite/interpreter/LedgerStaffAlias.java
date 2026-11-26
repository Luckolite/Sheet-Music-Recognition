// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Distinguishes a stack of ledger fragments from a separate staff adjoining proved rules. */
final class LedgerStaffAlias {
    private LedgerStaffAlias() {}

    static boolean unsupported(
            byte[] gray, int width, int height,
            float upperGap, PrintedStaffAdmission.Rules upper,
            float lowerGap, PrintedStaffAdmission.Rules lower) {
        if (gray == null || gray.length != (long) width * height
                || lowerGap < 8 || upperGap < lowerGap * .9f || upperGap > lowerGap * 1.1f)
            return false;
        // Candidate lines must track the established staff's own ledger phases.
        for (int sample = 1; sample < 8; sample++) {
            int x = width * sample / 8;
            float top = lower.y(0, x);
            float gap = (lower.y(4, x) - top) / 4;
            if (gap < 8) return false;
            for (int line = 0; line < 5; line++)
                if (Math.abs(upper.y(line, x) - (top - (5 - line) * gap)) > gap * .22f)
                    return false;
        }

        var ownInk = PrintedStaffAdmission.evidence(gray, width, height, 0, width - 1, upperGap, upper);
        // A tightly spaced real staff can occupy the same phases; its own four
        // simultaneous rails must take precedence, including visible short parts.
        if (ownInk.printed() || ownInk.alignedColumns() > width * .15f
                || ownInk.longestRun() >= upperGap * 1.5f) return false;
        var printed = PrintedStaffAdmission.evidence(gray, width, height, 0, width - 1, lowerGap, lower);
        return printed.printed() && printed.alignedColumns() >= width * .40f
                && printed.longestRun() >= ownInk.longestRun() * 4;
    }

    /**
     * Rejects a weak phase that reuses nearby staff lines or an unmeasured ledger extension.
     * The printed frame must have broad, simultaneous support across all of its rules.
     */
    static boolean weakerPhase(
            byte[] gray, int width, int height,
            float aliasGap, PrintedStaffAdmission.Rules alias,
            float printedGap, PrintedStaffAdmission.Rules printed,
            int offset, int aliasLeft, int aliasRight) {
        if (gray == null || gray.length != (long) width * height
                || printedGap < 8 || aliasGap < printedGap * .86f || aliasGap > printedGap * 1.14f)
            return false;
        for (int sample = 1; sample < 8; sample++) {
            int x = width * sample / 8;
            float top = printed.y(0, x), gap = (printed.y(4, x) - top) * .25f;
            if (gap < 8) return false;
            for (int line = 0; line < 5; line++)
                if (Math.abs(alias.y(line, x) - (top + (offset + line) * gap)) > gap * .44f)
                    return false;
        }
        int stride = Math.max(1, width / 768);
        int candidateColumns = completeColumns(gray, width, height, stride, aliasGap, alias);
        if ((long) candidateColumns * stride >= width * .27f) return false;
        if (aliasLeft >= 0 && aliasRight > aliasLeft) {
            int localColumns = completeColumns(gray, width, height, stride, aliasGap, alias,
                    aliasLeft, aliasRight);
            if ((long) localColumns * stride >= (aliasRight - aliasLeft) * .42f) return false;
        }
        int printedColumns = completeColumns(gray, width, height, stride, printedGap, printed);
        return (long) printedColumns * stride >= width * .55f
                && printedColumns >= 3 * candidateColumns;
    }


    /** Keeps the original printed staff when a local beam suggests a one-rule shift. */
    static boolean retainsBodyPhase(byte[] gray, int width, int height,
            float bottom, float gap, float proposed, float proposedGap, float slope) {
        if (gray == null || gap < 8 || Math.abs(proposedGap - gap) > gap * .07f
                || Math.abs(Math.abs(proposed - bottom) - gap) > gap * .20f
                || Math.abs(slope) * width > gap * .25f)
            return false;
        int stride = Math.max(1, width / 768);
        int original = completeColumns(gray, width, height, stride, gap,
                (line, x) -> bottom - (4 - line) * gap);
        if ((long) original * stride < width * .25f) return false;
        int retry = completeColumns(gray, width, height, stride, proposedGap,
                (line, x) -> proposed - (4 - line) * proposedGap + slope * (x - width * .5f));
        return (long) retry * stride < width * .18f && original > retry * 2;
    }

    private static int completeColumns(byte[] gray, int width, int height, int stride, float gap,
            PrintedStaffAdmission.Rules rules) {
        return completeColumns(gray, width, height, stride, gap, rules, 0, width);
    }

    private static int completeColumns(byte[] gray, int width, int height, int stride, float gap,
            PrintedStaffAdmission.Rules rules, int left, int right) {
        int radius = Math.max(1, Math.round(gap * .14f));
        int flank = Math.max(2, Math.round(gap * .32f));
        int columns = 0;
        for (int x = left; x < right; x += stride) {
            boolean complete = true;
            for (int line = 0; line < 5 && complete; line++) {
                int center = Math.round(rules.y(line, x));
                complete = false;
                for (int y = Math.max(flank, center - radius);
                        y <= Math.min(height - flank - 1, center + radius); y++) {
                    int pixel = y * width + x, ink = gray[pixel] & 255;
                    if (ink <= 225
                            && (gray[pixel - flank * width] & 255) >= ink + 12
                            && (gray[pixel + flank * width] & 255) >= ink + 12) {
                        complete = true;
                        break;
                    }
                }
            }
            if (complete) columns++;
        }
        return columns;
    }
}
