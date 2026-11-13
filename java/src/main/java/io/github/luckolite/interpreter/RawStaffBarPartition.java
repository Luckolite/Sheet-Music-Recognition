// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.Arrays;
import java.util.Set;

/** PRIVATE foreground-only partitions of complete raw cross-sections.
 * The caller must independently prove the staff stripe; a search axis supplies
 * no pixel authority. Every returned pixel was already in its complete raw run.
 * All width alternatives consistent with exposed body widths are intersected.
 */
final class RawStaffBarPartition {
    private RawStaffBarPartition() {}

    private static boolean take(long[] b, long n) {
        if (b[0] < n) {
            b[0] = -1;
            return false;
        }
        b[0] -= n;
        return true;
    }

    static boolean apply(
            int[] widths,
            int[] top,
            int[] bottom,
            int firstX,
            int pageWidth,
            int gap,
            int cap,
            Set<Integer> staff,
            long[] budget) {
        return apply(widths, top, bottom, firstX, pageWidth, gap, cap, staff, budget, null);
    }

    static boolean apply(
            int[] widths,
            int[] top,
            int[] bottom,
            int firstX,
            int pageWidth,
            int gap,
            int cap,
            Set<Integer> staff,
            long[] budget,
            StringBuilder reason) {
        return apply(widths, top, bottom, firstX, pageWidth, gap, cap, staff, budget, reason, null);
    }

    static boolean apply(
            int[] widths,
            int[] top,
            int[] bottom,
            int firstX,
            int pageWidth,
            int gap,
            int cap,
            Set<Integer> staff,
            long[] budget,
            StringBuilder reason,
            boolean[] excludeStaffMembers) {
        int n = widths.length;
        boolean any = false;
        boolean[] columnContact = null;
        // Distinct finite strokes must never constrain one another's width through
        // an intervening staff-only column. Such a column is UNKNOWN as bar ink.
        for (int i = 0; i < n; i++)
            if (widths[i] > 0) {
                long length = Math.max(0L, (long) bottom[i] - top[i] + 1);
                if (length > 0 && !take(budget, length)) {
                    if (reason != null) reason.append("BUDGET_FILTER");
                    return false;
                }
                boolean exposed = false, contact = false;
                int x = firstX + i;
                for (int y = top[i], pixel = y * pageWidth + x;
                        y <= bottom[i]; y++, pixel += pageWidth) {
                    if (staff.contains(pixel)) contact = true;
                    else exposed = true;
                    if (exposed && contact) break;
                }
                if (!exposed) widths[i] = 0;
                else if (contact) {
                    if (columnContact == null) columnContact = new boolean[n];
                    columnContact[i] = true;
                }
            }
        SegmentScratch scratch = null;
        for (int from = 0; from < n; ) {
            while (from < n && widths[from] == 0) from++;
            if (from == n) break;
            int to = from;
            boolean contact = false, withinCap = true;
            do {
                withinCap &= widths[to] <= cap;
                contact |= columnContact != null && columnContact[to];
                to++;
            } while (to < n && widths[to] > 0);
            int len = to - from;
            if (!take(budget, 4L * len)) {
                if (reason != null) reason.append("BUDGET_SEGMENT");
                return false;
            }
            boolean ok = withinCap;
            if (contact) {
                if (scratch == null || scratch.widths.length < len)
                    scratch = new SegmentScratch(len, reason != null);
                scratch.reset();
                ok = applyOne(scratch, widths, top, bottom, len, from, columnContact,
                        firstX + from, pageWidth, gap, cap, staff, budget);
                if (ok && scratch.coowned && excludeStaffMembers != null)
                    Arrays.fill(excludeStaffMembers, from, to, true);
            }
            if (reason != null && !ok)
                reason.append(contact ? scratch.reason : "WIDTH_OVER_CAP");
            if (budget[0] < 0) return false;
            // A failed write used to leave the affordable prefix in the caller's arrays.
            int written = (int) Math.min(budget[0], len);
            if (!ok) Arrays.fill(widths, from, from + written, 0);
            else if (contact && !scratch.coowned) {
                // Only contact columns can change. Their original endpoints
                // and staff bounds identify the anchor of each retained band.
                for (int i = 0; i < written; i++) {
                    int column = from + i;
                    if (!columnContact[column]) continue;
                    int width = scratch.widths[i];
                    if (width > 0) {
                        if (top[column] >= scratch.staffTop[i])
                            top[column] = bottom[column] - width + 1;
                        else if (bottom[column] <= scratch.staffBottom[i])
                            bottom[column] = top[column] + width - 1;
                    }
                    widths[column] = width;
                }
            }
            if (!take(budget, len)) {
                if (reason != null) reason.append("BUDGET_WRITE");
                return false;
            }
            any |= ok;
            from = to;
        }
        if (!any && reason != null && reason.length() == 0) reason.append("NO_EXPOSED_SEGMENT");
        return any;
    }

    private static final class SegmentScratch {
        final int[] widths;
        final int[] staffTop, staffBottom, exposedTop, exposedBottom;
        final StringBuilder reason;
        boolean coowned;

        SegmentScratch(int length, boolean diagnostics) {
            widths = new int[length];
            staffTop = new int[length];
            staffBottom = new int[length];
            exposedTop = new int[length];
            exposedBottom = new int[length];
            reason = diagnostics ? new StringBuilder() : null;
        }

        void reset() {
            if (reason != null) reason.setLength(0);
            coowned = false;
        }
    }

    private static boolean applyOne(
            SegmentScratch scratch, int[] inputWidths, int[] inputTop, int[] inputBottom,
            int n, int from, boolean[] columnContact,
            int firstX, int pageWidth, int gap, int cap,
            Set<Integer> staff, long[] budget) {
        int[] widths = scratch.widths;
        StringBuilder reason = scratch.reason;
        int min = Math.max(3, (int) Math.ceil(.2 * gap));
        int lower = min,
                upper = cap,
                exposedRun = 0,
                longest = 0,
                joined = 0,
                joinedRun = 0,
                longestJoined = 0;
        boolean coownedPossible = true;
        int[] staffTop = scratch.staffTop, staffBottom = scratch.staffBottom;
        int[] exposedTop = scratch.exposedTop, exposedBottom = scratch.exposedBottom;
        // The run arrays and stripe are read-only operands until all exposed widths
        // constrain the same finite physical band; no inferred white is introduced.
        for (int i = 0; i < n; i++) {
            int column = from + i;
            int a = inputTop[column], b = inputBottom[column], width = inputWidths[column];
            int length = Math.max(0, b - a + 1);
            long available = budget[0];
            // Segments contain only nonzero widths. A short budget still
            // distinguishes the column charge from the pixel scan.
            if (!take(budget, 1L + length)) {
                if (reason != null)
                    reason.append(available < 1 ? "BUDGET_WIDTH_PROFILE" : "BUDGET_CONTACT_SCAN");
                return false;
            }
            boolean contact = columnContact[column];
            int residual = length, bodyTop = a, bodyBottom = b;
            int firstStaff = Integer.MAX_VALUE, lastStaff = -1;
            if (contact) {
                residual = 0;
                bodyTop = Integer.MAX_VALUE;
                bodyBottom = -1;
                int x = firstX + i;
                for (int y = a, pixel = y * pageWidth + x; y <= b; y++, pixel += pageWidth) {
                    if (staff.contains(pixel)) {
                        if (y < firstStaff) firstStaff = y;
                        lastStaff = y;
                    } else {
                        residual++;
                        if (y < bodyTop) bodyTop = y;
                        bodyBottom = y;
                    }
                }
                staffTop[i] = firstStaff;
                staffBottom[i] = lastStaff;
                exposedTop[i] = bodyTop;
                exposedBottom[i] = bodyBottom;
            }
            if (coownedPossible
                    && (length > 2 * cap || residual < min || residual > cap
                            || bodyBottom < bodyTop || bodyBottom - bodyTop + 1 > cap))
                coownedPossible = false;
            if (!contact && width >= min && width <= cap) {
                lower = Math.max(lower, width - 1);
                upper = Math.min(upper, width + 1);
                longest = Math.max(longest, ++exposedRun);
            } else {
                exposedRun = 0;
                if (contact) {
                    joined++;
                    joinedRun++;
                    longestJoined = Math.max(longestJoined, joinedRun);
                } else joinedRun = 0;
            }
        }
        if (longest < min || joined == 0 || lower > upper) {
            // Avoid rescanning every weak/partial candidate. A separate co-owned run
            // is considered only when one continuous witnessed staff contact covers
            // all but the same short cap allowance used by the exposed-run minimum.
            if (coownedPossible
                    && longestJoined >= Math.max(3, n - min + 1)
                    && n >= Math.max(2, (int) Math.rint(.45 * 1.4 * gap))) {
                if (reason != null) reason.append("COOWNED_COMPLETE_RUN");
                scratch.coowned = true;
                return true;
            }
            if (reason != null)
                reason.append("NO_COMMON_WIDTH_OR_EXPOSED_RUN[longest=")
                        .append(longest)
                        .append(",joined=")
                        .append(joined)
                        .append(",lower=")
                        .append(lower)
                        .append(",upper=")
                        .append(upper)
                        .append("]");
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!columnContact[from + i]) {
                // These columns are unchanged. Keep the budget failure diagnostic
                // from the exact column and stage where reconstruction would stop.
                long charge = 0;
                do {
                    if (charge >= budget[0]) {
                        budget[0] = -1;
                        if (reason != null) reason.append("BUDGET_RECONSTRUCT");
                        return false;
                    }
                    int column = from + i;
                    charge += 1L + Math.max(0L, (long) inputBottom[column] - inputTop[column] + 1);
                    if (charge > budget[0]) {
                        budget[0] = -1;
                        if (reason != null) reason.append("BUDGET_RECONSTRUCT_SCAN");
                        return false;
                    }
                    i++;
                } while (i < n && !columnContact[from + i]);
                budget[0] -= charge;
                i--;
                continue;
            }
            int a = inputTop[from + i], b = inputBottom[from + i];
            int lo = staffTop[i], hi = staffBottom[i];
            long length = (long) b - a + 1;
            long available = budget[0];
            if (!take(budget, 1 + length)) {
                if (reason != null)
                    reason.append(available < 1 ? "BUDGET_RECONSTRUCT" : "BUDGET_RECONSTRUCT_SCAN");
                return false;
            }
            // A sparse carrier center cannot be inflated into a stripe. Every residual
            // pixel must individually have independent staff membership.
            boolean above = a < lo, below = b > hi;
            if (!above && !below) {
                widths[i] = 0;
                continue;
            }
            int acceptedWidth = 0;
            int runLength = b - a + 1;
            int firstWidth = above && below ? Math.max(lower, runLength) : lower;
            int lastWidth = Math.min(upper, runLength);
            // Widths outside the run still consume one unit each, without a pixel scan.
            int leading = Math.min(firstWidth - lower, upper - lower + 1);
            if (leading > 0 && !take(budget, leading)) return false;
            int exposedFirst = exposedTop[i], exposedLast = exposedBottom[i];
            if (!above && firstWidth <= lastWidth) {
                // Bottom-anchored bands reject narrower widths at the same first
                // exposed pixel. Their scan and width charges can be paid together.
                int firstComplete = b - exposedFirst + 1;
                int rejectedWidths = (int) (Math.min(lastWidth + 1L, firstComplete) - firstWidth);
                if (rejectedWidths > 0) {
                    if (!take(budget, (long) rejectedWidths * (2L + exposedFirst - a)))
                        return false;
                    firstWidth += rejectedWidths;
                }
            }
            int suffixCursor = exposedFirst;
            int x = firstX + i;
            for (int thick = firstWidth; thick <= lastWidth; thick++) {
                if (!take(budget, 1)) return false;
                int aa = above ? a : b - thick + 1, bb = below ? b : a + thick - 1;
                if (exposedFirst >= aa && exposedLast <= bb) {
                    // These bands are nested. The first accepted width is their
                    // intersection, and all wider feasible bands scan the full run.
                    long remainingWidths = lastWidth - (long) thick;
                    long fullScans = length + remainingWidths * (1 + length);
                    if (!take(budget, fullScans)) return false;
                    acceptedWidth = thick;
                    break;
                }
                int rejectedAt = exposedFirst;
                if (exposedFirst >= aa) {
                    // The suffix can only shrink as the tested band grows.
                    suffixCursor = Math.max(bb + 1, suffixCursor);
                    int pixel = suffixCursor * pageWidth + x;
                    while (suffixCursor < exposedLast && staff.contains(pixel)) {
                        suffixCursor++;
                        pixel += pageWidth;
                    }
                    rejectedAt = suffixCursor;
                }
                if (!take(budget, (long) rejectedAt - a + 1)) return false;
            }
            int trailing = upper - Math.max(lastWidth, lower + leading - 1);
            if (trailing > 0 && !take(budget, trailing)) return false;
            if (acceptedWidth == 0) {
                if (reason != null) reason.append("NO_COMPLETE_RESIDUAL_AT_X_").append(firstX + i);
                widths[i] = 0;
                continue;
            }
            widths[i] = acceptedWidth;
        }
        return true;
    }
}
