// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
// Adapted from Music Sheets: standalone package and platform-independent diagnostics.
package io.github.luckolite.interpreter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recovers long five-line staffs directly from page pixels when the semantic mask is incomplete. */
final class RawStaffLineDetector {
    private static final int DARK = 170;
    private static final VarHandle GRAY_WORDS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());
    private static final long BYTE_HIGH_BITS = 0x8080808080808080L;
    private static final long BRIGHT_LOW_BITS = 0x0101010101010101L * (DARK + 1 - 128);

    private RawStaffLineDetector() {}

    record StaffLines(int[] rows, float gap) {
        int top() {
            return rows[0];
        }

        int bottom() {
            return rows[4];
        }

        float center() {
            return (rows[0] + rows[4]) * .5f;
        }
    }

    private record Candidate(int[] rows, float gap, float score) {}
    private record RuleProbe(int row, int flank, int minimumRun) {}
    private static final Comparator<Candidate> BY_DESCENDING_SCORE =
            Comparator.comparingDouble(Candidate::score).reversed();

    static List<StaffLines> detect(byte[] gray, int width, int height) {
        if (gray == null || width < 24 || height < 5 || gray.length != (long) width * height)
            return List.of();

        int minimumStrength = Math.max(24, Math.round(width * .25f));
        int[] rowStrength = new int[height];
        int rowsNeeded = 5;
        for (int y = 0, rowStart = 0; y < height; y++, rowStart += width) {
            int strength = countDarkRow(gray, rowStart, width);
            rowStrength[y] = strength;
            if (rowsNeeded > 0) {
                if (strength >= minimumStrength) rowsNeeded--;
                if (height - 1 - y < rowsNeeded) return List.of();
            }
        }

        List<StaffLines> detected =
                detectFromStrength(rowStrength, minimumStrength, height, false, gray, width);
        if (detected.size() < 4) return detected;
        List<StaffLines> result = new ArrayList<>(detected);
        retainDominantStaffScale(result, gray, width, height);
        return List.copyOf(result);
    }

    private static int countDarkRow(byte[] gray, int start, int width) {
        int at = start, end = start + width, strength = 0;
        int wordEnd = start + width - width % Long.BYTES;
        for (; at < wordEnd; at += Long.BYTES) {
            long word = (long) GRAY_WORDS.get(gray, at);
            // Set each high bit before subtracting so no byte can borrow from its neighbor.
            long bright = ((word | BYTE_HIGH_BITS) - BRIGHT_LOW_BITS) & word & BYTE_HIGH_BITS;
            strength += Long.BYTES - Long.bitCount(bright);
        }
        for (; at < end; at++) {
            strength += ((gray[at] & 0xff) - (DARK + 1)) >>> 31;
        }
        return strength;
    }

    /** Finds five-line groups in an already deskewed projection. Shared with the semantic
     * postprocessor so stray staff-labelled notation between lines cannot merge a whole system
     * into one continuous projection band. */
    static List<StaffLines> detectFromStrength(int[] rowStrength, int minimumStrength, int height) {
        return detectFromStrength(rowStrength, minimumStrength, height, null);
    }

    static List<StaffLines> detectFromStrength(
            int[] rowStrength,
            int minimumStrength,
            int height,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        return detectFromStrength(rowStrength, minimumStrength, height, true, quarterBudget);
    }

    /** A few staff-labelled slurs can invent a sixth-to-tenth-rule group in the
     * narrow whitespace between two independently printed staves. */
    static List<StaffLines> detectFromStrength(
            int[] strength, int minimum, int height, byte[] gray, int width) {
        return detectFromStrength(strength, minimum, height, gray, width, null);
    }

    static List<StaffLines> detectFromStrength(
            int[] strength,
            int minimum,
            int height,
            byte[] gray,
            int width,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
        if (quarterBudget != null && gray != null) throw new FaintQuarterRestInk.Refusal();

        List<StaffLines> original = detectFromStrength(strength, minimum, height, quarterBudget);
        if (gray == null || width <= 0 || gray.length != width * height) return original;
        List<StaffLines> result =
                (quarterBudget == null
                                || quarterBudget.allocate(
                                        1L + 4L * original.size(), 256L + 32L * original.size())
                        ? new ArrayList<>(original)
                        : null);
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int i = 1; i + 1 < original.size(); i++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                StaffLines a = original.get(i - 1), b = original.get(i), c = original.get(i + 1);
                float gap = Math.max(b.gap(), Math.max(a.gap(), c.gap()));
                int above = b.top() - a.bottom(), below = c.top() - b.bottom();
                if (above <= 0 || below <= 0 || above > gap * 1.5f || below > gap * 1.5f) continue;
                if (printedRuleCount(
                                        (quarterBudget == null || quarterBudget.allocate(1, 256)
                                                ? new Candidate(b.rows(), b.gap(), 0)
                                                : null),
                                        gray,
                                        width,
                                        height,
                                        230)
                                <= 1
                        && printedRuleCount(
                                        (quarterBudget == null || quarterBudget.allocate(1, 256)
                                                ? new Candidate(a.rows(), a.gap(), 0)
                                                : null),
                                        gray,
                                        width,
                                        height)
                                >= 4
                        && printedRuleCount(
                                        (quarterBudget == null || quarterBudget.allocate(1, 256)
                                                ? new Candidate(c.rows(), c.gap(), 0)
                                                : null),
                                        gray,
                                        width,
                                        height)
                                >= 4) FaintQuarterRestInk.remove(quarterBudget, result, b);
            }
        }

        rejectUnsupportedAliases(result, gray, width, height);
        return (quarterBudget == null
                        || quarterBudget.allocate(
                                1L + 4L * (result).size(), 128L + 32L * (result).size())
                ? List.copyOf(result)
                : null);
    }

    /** Replace only compressed semantic aliases independently contradicted by five printed rules. */
    static List<StaffLines> detectValidatedFromStrength(
            int[] strength, int minimum, int height, byte[] gray, int width) {
        List<StaffLines> original = detectFromStrength(strength, minimum, height);
        List<StaffLines> printed =
                detectFromStrength(strength, minimum, height, false, gray, width);
        List<StaffLines> result = new ArrayList<>(original);
        for (StaffLines proof : printed) {
            List<StaffLines> aliases = new ArrayList<>();
            for (StaffLines existing : result)
                if (existing.gap() < proof.gap() * .68f
                        && existing.top() >= proof.top() - proof.gap() * 2.2f
                        && existing.bottom() <= proof.bottom() + proof.gap() * 2.2f)
                    aliases.add(existing);
            if (aliases.isEmpty()) continue;
            result.removeAll(aliases);
            boolean represented = false;
            for (StaffLines existing : result)
                if (Math.abs(existing.center() - proof.center()) <= proof.gap() * 2.2f
                        && Math.abs(existing.gap() - proof.gap()) <= proof.gap() * .18f)
                    represented = true;
            if (!represented) result.add(proof);
        }
        result.sort(Comparator.comparingInt(StaffLines::top));
        rejectUnsupportedAliases(result, gray, width, height);
        return List.copyOf(result);
    }

    /** Two independent printed staves distinguish page notation from unrelated semantic texture. */
    private static void rejectUnsupportedAliases(
            List<StaffLines> staffs, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return;
        var evidence = new AliasEvidence(gray, width, height);
        int proved = 0, first = height, last = -1;
        for (var staff : staffs)
            if (evidence.darkRules(staff) >= 4) {
                proved++;
                first = Math.min(first, staff.top());
                last = Math.max(last, staff.bottom());
            }
        // The semantic model can omit every real staff while labeling page artwork.
        // Independent complete printed staves can still establish the notation envelope.
        if (proved < 2) {
            int rawProved = 0, rawFirst = height, rawLast = -1;
            for (var staff : detect(gray, width, height)) {
                if (evidence.darkRules(staff) < 4) continue;
                rawProved++;
                rawFirst = Math.min(rawFirst, staff.top());
                rawLast = Math.max(rawLast, staff.bottom());
            }
            if (rawProved >= 2) {
                proved = rawProved;
                first = rawFirst;
                last = rawLast;
            }
        }
        if (proved < 2) return;
        rejectIrregularInteriorAliases(staffs, evidence);
        rejectUnsupportedInteriorTexture(staffs, evidence);
        final int firstPrinted = first, lastPrinted = last;
        // Interior gaps may contain faint parts; this rejection only handles outer texture.
        staffs.removeIf(
                staff -> {
                    if (staff.bottom() >= firstPrinted && staff.top() <= lastPrinted) return false;
                    int rules = evidence.paleRules(staff);
                    return (rules == 0
                                    || rules <= 2 && ConvergingStaffStrokes.matches(
                                            gray, width, height, staff.rows(), staff.gap()))
                                && !hasLocalContrastedRule(staff, gray, width, height)
                                && StaffPitchTrack.detectForMeasures(
                                                gray,
                                                width,
                                                height,
                                                staff.top(),
                                                staff.bottom(),
                                                staff.gap())
                                        == null;
                });
    }

    /** Apply the existing printed-rule/texture proof to the regular frame emitted downstream. */
    static boolean unsupportedInteriorFrame(
            StaffLines above,
            StaffLines candidate,
            StaffLines below,
            byte[] gray,
            int width,
            int height) {
        if (above == null
                || candidate == null
                || below == null
                || gray == null
                || width <= 0
                || height <= 0
                || gray.length != (long) width * height
                || above.bottom() >= candidate.top()
                || below.top() <= candidate.bottom()) return false;
        var frames = new ArrayList<>(List.of(above, candidate, below));
        rejectUnsupportedInteriorTexture(frames, new AliasEvidence(gray, width, height));
        return !frames.contains(candidate);
    }

    /** Multiple texture groups can sit between real systems; each needs its own printed support. */
    private static void rejectUnsupportedInteriorTexture(
            List<StaffLines> staffs, AliasEvidence evidence) {
        if (staffs.size() < 3) return;
        byte[] gray = evidence.gray;
        int width = evidence.width, height = evidence.height;
        int firstProofBottom = height, lastProofTop = -1;
        for (var staff : staffs) {
            if (evidence.paleRules(staff) >= 4 && evidence.parallelRules(staff)) {
                firstProofBottom = Math.min(firstProofBottom, staff.bottom());
                lastProofTop = Math.max(lastProofTop, staff.top());
            }
        }
        if (firstProofBottom >= lastProofTop) return;
        int upperBoundary = firstProofBottom, lowerBoundary = lastProofTop;
        staffs.removeIf(
                staff -> staff.top() > upperBoundary
                            && staff.bottom() < lowerBoundary
                            && evidence.paleRules(staff) <= 1
                            && hasBackgroundTexture(staff, gray, width, height)
                            && !hasLocalContrastedRule(staff, gray, width, height)
                            && !evidence.parallelRules(staff)
                            && StaffPitchTrack.detectForMeasures(
                                            gray,
                                            width,
                                            height,
                                            staff.top(),
                                            staff.bottom(),
                                            staff.gap())
                                    == null);
    }

    /** Distributed local variation supplies positive image-texture evidence, not just missing rules. */
    private static boolean hasBackgroundTexture(
            StaffLines staff, byte[] gray, int width, int height) {
        int size = Math.max(4, Math.round(staff.gap()));
        int top = Math.max(0, staff.top()), lastY = Math.min(staff.bottom(), height - size);
        if (top > lastY || size > width) return false;
        int stride = size <= width / 2 ? size * 2 : width;
        int total = (1 + (lastY - top) / size) * (1 + (width - size) / stride);
        int required = Math.max(16, (int) Math.ceil(total * .2));
        if (total < required) return false;
        int count = size * size, remaining = total, textured = 0;
        for (int y = top; y <= lastY; y += size)
            for (int x = 0; x <= width - size; x += stride) {
                long sum = 0, squares = 0;
                for (int yy = y; yy < y + size; yy++) {
                    int start = yy * width + x;
                    for (int p = start, end = start + size; p < end; p++) {
                        int value = gray[p] & 255;
                        sum += value;
                        squares += (long) value * value;
                    }
                }
                double mean = sum / (double) count;
                if (squares / (double) count - mean * mean > 25 && ++textured >= required)
                    return true;
                if (textured + --remaining < required) return false;
            }
        return false;
    }

    /** Interior semantic texture must contradict both neighboring printed scales before rejection. */
    private static void rejectIrregularInteriorAliases(
            List<StaffLines> staffs, AliasEvidence evidence) {
        byte[] gray = evidence.gray;
        int width = evidence.width, height = evidence.height;
        var original = new ArrayList<>(staffs);
        for (int i = 1; i + 1 < original.size(); i++) {
            var a = original.get(i - 1);
            var b = original.get(i);
            var c = original.get(i + 1);
            if (b.gap() >= Math.min(a.gap(), c.gap()) * .85f) continue;
            int smallest = Integer.MAX_VALUE, largest = 0;
            for (int j = 1; j < 5; j++) {
                int delta = b.rows()[j] - b.rows()[j - 1];
                smallest = Math.min(smallest, delta);
                largest = Math.max(largest, delta);
            }
            if (smallest >= b.gap() * .7f || largest <= b.gap() * 1.2f) continue;
            if (evidence.darkRules(a) < 4 || evidence.darkRules(c) < 4
                    || evidence.paleRules(b) > 0) continue;
            if (StaffPitchTrack.detectForMeasures(gray, width, height, b.top(), b.bottom(), b.gap())
                            != null
                    || evidence.parallelRules(b)) continue;
            staffs.remove(b);
        }
    }

    private static final class AliasEvidence {
        final byte[] gray;
        final int width, height;
        private final Map<StaffLines, Integer> dark = new HashMap<>();
        private final Map<StaffLines, Integer> pale = new HashMap<>();
        private final Map<StaffLines, Boolean> parallel = new HashMap<>();

        AliasEvidence(byte[] gray, int width, int height) {
            this.gray = gray;
            this.width = width;
            this.height = height;
        }

        int darkRules(StaffLines staff) {
            return dark.computeIfAbsent(staff, key -> countRules(key, DARK));
        }

        int paleRules(StaffLines staff) {
            return pale.computeIfAbsent(staff, key -> countRules(key, 230));
        }

        boolean parallelRules(StaffLines staff) {
            return parallel.computeIfAbsent(
                    staff, key -> hasFiveParallelLocalRules(key, gray, width, height));
        }

        private int countRules(StaffLines staff, int limit) {
            return printedRuleCount(new Candidate(staff.rows(), staff.gap(), 0),
                    gray, width, height, limit);
        }
    }

    /** Short or pale real staves can retain five parallel rules without a long page projection. */
    private static boolean hasFiveParallelLocalRules(
            StaffLines staff, byte[] gray, int width, int height) {
        int phase = Math.max(1, Math.round(staff.gap() * .5f)),
                radius = Math.max(1, Math.round(staff.gap() * .15f));
        int flank = Math.max(2, Math.round(staff.gap() * .35f)),
                needed = Math.max(12, Math.round(staff.gap() * 4));
        int[] firstRows = new int[5], lastRows = new int[5];
        for (int shift = -phase; shift <= phase; shift++) {
            for (int line = 0; line < 5; line++) {
                int center = Math.round(staff.top() + shift + line * staff.gap());
                firstRows[line] = Math.max(flank, center - radius);
                lastRows[line] = Math.min(height - flank - 1, center + radius);
            }
            int run = 0;
            for (int x = 0; x < width; x++) {
                boolean all = true;
                for (int line = 0; line < 5; line++) {
                    boolean hit = false;
                    for (int y = firstRows[line]; y <= lastRows[line]; y++) {
                        int value = gray[y * width + x] & 255;
                        if ((gray[(y - flank) * width + x] & 255) >= value + 4
                                && (gray[(y + flank) * width + x] & 255) >= value + 4) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) {
                        all = false;
                        break;
                    }
                }
                run = all ? run + 1 : 0;
                if (run >= needed) return true;
            }
        }
        return false;
    }

    /** Parallel local contrast preserves faint or short rules without treating unrelated texture as a staff. */
    private static boolean hasLocalContrastedRule(
            StaffLines staff, byte[] gray, int width, int height) {
        int radius = Math.max(1, Math.round(staff.gap() * .35f)),
                flank = Math.max(2, Math.round(staff.gap() * .35f));
        int[] rows = staff.rows();
        int[] starts = new int[rows.length], ends = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            starts[i] = Math.max(flank, rows[i] - radius);
            ends[i] = Math.min(height - flank - 1, rows[i] + radius);
        }
        int minimumCount = Math.max(24, Math.round(staff.gap() * 4));
        int minimumRunThree = Math.max(12, Math.round(staff.gap() * 8));
        int minimumRunFour = Math.max(12, Math.round(staff.gap() * 3));
        int countThree = 0, runThree = 0, countFour = 0, runFour = 0;
        boolean hasRunThree = false, hasRunFour = false;
        for (int x = 0; x < width; x++) {
            int hits = 0;
            for (int i = 0; i < rows.length && hits + rows.length - i >= 3; i++) {
                for (int y = starts[i]; y <= ends[i]; y++) {
                    int value = gray[y * width + x] & 255;
                    if ((gray[(y - flank) * width + x] & 255) >= value + 4
                            && (gray[(y + flank) * width + x] & 255) >= value + 4) {
                        hits++;
                        break;
                    }
                }
            }
            if (hits >= 3) {
                countThree++;
                if (++runThree >= minimumRunThree) hasRunThree = true;
                if (countThree >= minimumCount && hasRunThree) return true;
            } else runThree = 0;
            if (hits >= 4) {
                countFour++;
                if (++runFour >= minimumRunFour) hasRunFour = true;
                if (countFour >= minimumCount && hasRunFour) return true;
            } else runFour = 0;
        }
        return false;
    }

    private static List<StaffLines> detectFromStrength(
            int[] rowStrength, int minimumStrength, int height, boolean filterScale) {
        return detectFromStrength(rowStrength, minimumStrength, height, filterScale, null);
    }

    private static List<StaffLines> detectFromStrength(
            int[] rowStrength,
            int minimumStrength,
            int height,
            boolean filterScale,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        return detectFromStrength(
                rowStrength, minimumStrength, height, filterScale, null, 0, quarterBudget);
    }

    private static List<StaffLines> detectFromStrength(
            int[] rowStrength,
            int minimumStrength,
            int height,
            boolean filterScale,
            byte[] gray,
            int width) {
        return detectFromStrength(
                rowStrength, minimumStrength, height, filterScale, gray, width, null);
    }

    private static List<StaffLines> detectFromStrength(
            int[] rowStrength,
            int minimumStrength,
            int height,
            boolean filterScale,
            byte[] gray,
            int width,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
        if (quarterBudget != null && gray != null) throw new FaintQuarterRestInk.Refusal();

        if (rowStrength == null || rowStrength.length != height || minimumStrength <= 0)
            return (quarterBudget == null || quarterBudget.allocate(1, 128) ? List.of() : null);
        List<Integer> peaks = localPeaks(rowStrength, minimumStrength, quarterBudget);
        List<Candidate> candidates =
                staffCandidates(peaks, rowStrength, minimumStrength, height, quarterBudget);
        if (quarterBudget == null && candidates.isEmpty()) return List.of();

        // Highest-confidence candidate wins when nearby notation creates multiple possible groups
        // around one staff. Staffs cannot overlap, so accepting the best groups first avoids an
        // ornament above a staff shifting all five selected rows by one position.
        FaintQuarterRestInk.sort(
                quarterBudget,
                candidates,
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? BY_DESCENDING_SCORE
                        : null));
        List<StaffLines> result =
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? new ArrayList<>()
                        : null);
        Map<RuleProbe, Boolean> ruleEvidence = gray == null ? null : new HashMap<>();
        int minimumRuleSupport = Math.max(24, Math.round(width * .12f));
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 64);
            for (Candidate candidate : candidates) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                int top = candidate.rows()[0], bottom = candidate.rows()[4];
                float gap = candidate.gap();
                boolean overlaps = false;
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 64);
                    for (StaffLines accepted : result) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                        float margin = Math.min(gap, accepted.gap()) * .75f;
                        if (top <= accepted.bottom() + margin
                                && bottom >= accepted.top() - margin) {
                            overlaps = true;
                            break;
                        }
                    }
                }

                if (!overlaps && gray != null
                        && !hasPrintedRules(candidate, gray, width, height, minimumRuleSupport,
                                ruleEvidence)) continue;
                if (!overlaps)
                    FaintQuarterRestInk.add(
                            quarterBudget,
                            result,
                            (quarterBudget == null || quarterBudget.allocate(1, 256)
                                    ? new StaffLines(candidate.rows(), gap)
                                    : null));
            }
        }

        if (filterScale) retainDominantStaffScale(result, null, 0, 0, quarterBudget);
        FaintQuarterRestInk.sort(
                quarterBudget,
                result,
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? Comparator.comparingInt(StaffLines::top)
                        : null));
        return (quarterBudget == null
                        || quarterBudget.allocate(
                                1L + 4L * (result).size(), 128L + 32L * (result).size())
                ? List.copyOf(result)
                : null);
    }

    /** Shadows can form periodic dark projection peaks without any thin printed rules. */
    private static boolean hasPrintedRules(
            Candidate candidate, byte[] gray, int width, int height, int minimum,
            Map<RuleProbe, Boolean> evidence) {
        int flank = Math.max(2, Math.round(candidate.gap() * .32f));
        int minimumRun = Math.max(12, Math.round(candidate.gap() * 3));
        for (int row : candidate.rows()) {
            var probe = new RuleProbe(row, flank, minimumRun);
            Boolean supported = evidence.get(probe);
            if (supported == null) {
                supported = printedRuleAt(gray, width, height, row, flank, minimum, minimumRun, DARK);
                if (evidence.size() < 4096) evidence.put(probe, supported);
            }
            if (!supported) return false;
        }
        return true;
    }

    private static int printedRuleCount(Candidate candidate, byte[] gray, int width, int height) {
        return printedRuleCount(candidate, gray, width, height, DARK);
    }

    private static int printedRuleCount(
            Candidate candidate, byte[] gray, int width, int height, int inkLimit) {
        int supported = 0;
        int probe = Math.max(2, Math.round(candidate.gap() * .32f));
        int minimum = Math.max(24, Math.round(width * .12f));
        int minimumRun = Math.max(12, Math.round(candidate.gap() * 3));
        for (int y : candidate.rows())
            if (printedRuleAt(gray, width, height, y, probe, minimum, minimumRun, inkLimit))
                supported++;
        return supported;
    }

    private static boolean printedRuleAt(
            byte[] gray, int width, int height, int y, int probe,
            int minimum, int minimumRun, int inkLimit) {
        if (y < probe || y >= height - probe || minimum > width || minimumRun > width)
            return false;
        int row = y * width, flankOffset = probe * width;
        int end = row + width, remaining = minimum, run = 0;
        boolean continuous = false;
        for (int at = row; at < end; at++) {
            int ink = gray[at] & 255;
            if (ink <= inkLimit
                    && (gray[at - flankOffset] & 255) >= ink + 12
                    && (gray[at + flankOffset] & 255) >= ink + 12) {
                remaining--;
                if (!continuous && ++run >= minimumRun) continuous = true;
                // Require both a continuous segment and aggregate page-wide support.
                if (remaining <= 0 && continuous) return true;
            } else {
                run = 0;
                int columnsLeft = end - at - 1;
                if (columnsLeft < remaining || (!continuous && columnsLeft < minimumRun))
                    return false;
            }
        }
        return false;
    }

    /**
     * A page is engraved at one staff scale even when its measure widths and note density vary.
     * Four stacked arpeggio beams can also make five long, regularly spaced projection peaks,
     * but their gap is much smaller than every real staff on the page. Keep the page's dominant
     * scale so those beam bands cannot become extra systems while still allowing raw recovery of
     * a staff that the model omitted completely.
     */
    private static void retainDominantStaffScale(
            List<StaffLines> staffs, byte[] gray, int width, int height) {
        retainDominantStaffScale(staffs, gray, width, height, null);
    }

    private static void retainDominantStaffScale(
            List<StaffLines> staffs,
            byte[] gray,
            int width,
            int height,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
        if (quarterBudget != null && gray != null) throw new FaintQuarterRestInk.Refusal();

        if (staffs == null || staffs.size() < 4) return;
        List<Float> gaps =
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? new ArrayList<>()
                        : null);
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 64);
            for (StaffLines staff : staffs) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                FaintQuarterRestInk.add(
                        quarterBudget,
                        gaps,
                        FaintQuarterRestInk.boxFloat(quarterBudget, staff.gap()));
            }
        }

        FaintQuarterRestInk.sort(
                quarterBudget,
                gaps,
                (quarterBudget == null || quarterBudget.allocate(1, 256) ? Float::compare : null));
        float median = gaps.get(gaps.size() / 2);
        int consistent = 0;
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 64);
            for (float gap : gaps) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                if (gap >= median * .68f && gap <= median * 1.47f) consistent++;
            }
        }

        if (consistent * 2 < staffs.size()) return;
        List<StaffLines> candidates =
                (quarterBudget == null
                                || quarterBudget.allocate(
                                        1L + 4L * (staffs).size(), 128L + 32L * (staffs).size())
                        ? List.copyOf(staffs)
                        : null);
        FaintQuarterRestInk.removeIf(
                quarterBudget,
                staffs,
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? staff ->
                                (staff.gap() < median * .68f || staff.gap() > median * 1.47f)
                                        && !(gray != null
                                                && staff.gap() >= median * .55f
                                                && staff.gap() < median * .68f
                                                && connectedToStaff(
                                                        staff, candidates, gray, width, height))
                        : null));
    }

    /** A cue-sized part is real when a printed system rule joins it to another five-line staff. */
    static boolean connectedToStaff(
            StaffLines staff, List<StaffLines> candidates, byte[] gray, int width, int height) {
        if (gray == null) return false;
        int staffEdge = Integer.MIN_VALUE;
        for (StaffLines other : candidates) {
            if (other == staff) continue;
            StaffLines upper = staff.top() < other.top() ? staff : other,
                    lower = upper == staff ? other : staff;
            float gap = Math.max(upper.gap(), lower.gap());
            int separation = lower.top() - upper.bottom();
            if (separation <= 0 || separation > gap * 16) continue;
            int top = Math.max(0, Math.round(upper.bottom() + gap * .12f));
            int bottom = Math.min(height - 1, Math.round(lower.top() - gap * .12f));
            int radius = Math.max(2, Math.round(gap * 2));
            // The queried staff and raster stay unchanged throughout this connection search.
            // Read both edges in their original order before examining either connector.
            int upperEdge =
                    upper == staff && staffEdge != Integer.MIN_VALUE
                            ? staffEdge
                            : leftEdge(upper, gray, width, height);
            int lowerEdge =
                    lower == staff && staffEdge != Integer.MIN_VALUE
                            ? staffEdge
                            : leftEdge(lower, gray, width, height);
            staffEdge = upper == staff ? upperEdge : lowerEdge;
            int firstStart = Math.max(0, upperEdge - radius);
            int firstEnd = Math.min(width - 1, upperEdge + radius);
            if (upperEdge >= 0
                    && hasConnector(gray, width, firstStart, firstEnd, top, bottom, gap)) return true;
            if (lowerEdge >= 0) {
                int secondStart = Math.max(0, lowerEdge - radius);
                int secondEnd = Math.min(width - 1, lowerEdge + radius);
                if (upperEdge < 0) {
                    if (hasConnector(gray, width, secondStart, secondEnd, top, bottom, gap))
                        return true;
                } else if (hasConnector(gray, width, secondStart, Math.min(secondEnd, firstStart - 1),
                                top, bottom, gap)
                        || hasConnector(gray, width, Math.max(secondStart, firstEnd + 1), secondEnd,
                                top, bottom, gap)) return true;
            }
        }
        return false;
    }

    private static boolean hasConnector(
            byte[] gray, int width, int start, int end, int top, int bottom, float gap) {
        for (int x = start; x <= end; x++)
            if (OmrMeasurePostProcessor.verticalRuleAt(gray, width, x, top, bottom, gap)) return true;
        return false;
    }

    private static int leftEdge(StaffLines staff, byte[] gray, int width, int height) {
        int[] rows = staff.rows();
        int[] starts = new int[rows.length], ends = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            starts[i] = Math.max(0, rows[i] - 1) * width;
            ends[i] = Math.min(height - 1, rows[i] + 1) * width;
        }
        for (int x = 0; x < width; x++) {
            int lines = 0;
            for (int i = 0; i < rows.length && lines + rows.length - i >= 4; i++) {
                for (int row = starts[i]; row <= ends[i]; row += width) {
                    if ((gray[row + x] & 255) > DARK) continue;
                    if (++lines == 4) return x;
                    break;
                }
            }
        }
        return -1;
    }

    private static List<Integer> localPeaks(int[] strength, int minimumStrength) {
        return localPeaks(strength, minimumStrength, null);
    }

    private static List<Integer> localPeaks(
            int[] strength, int minimumStrength, FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        List<Integer> raw =
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? new ArrayList<>()
                        : null);
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int row = 0; row < strength.length; row++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                int rowStrength = strength[row];
                if (rowStrength < minimumStrength) continue;
                boolean maximum = true, prominent = false;
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int check = Math.max(0, row - 2);
                            check <= Math.min(strength.length - 1, row + 2);
                            check++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                        if (strength[check] > rowStrength) {
                            maximum = false;
                            if (quarterBudget == null) break;
                        }
                        if (strength[check] < rowStrength) prominent = true;
                    }
                }

                if (maximum && prominent) {
                    if (quarterBudget == null) mergePeak(raw, row, strength, null);
                    else FaintQuarterRestInk.add(
                            quarterBudget, raw, FaintQuarterRestInk.boxInt(quarterBudget, row));
                }
            }
        }

        if (quarterBudget == null) return raw;
        // A thick or antialiased staff line may have two equally dark center rows. Keep only its
        // strongest center before looking for the five-line periodic pattern.
        List<Integer> merged =
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? new ArrayList<>()
                        : null);
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 64);
            for (int row : raw) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                mergePeak(merged, row, strength, quarterBudget);
            }
        }

        return merged;
    }

    private static void mergePeak(
            List<Integer> peaks, int row, int[] strength, FaintQuarterRestInk.Budget budget) {
        int last = peaks.size() - 1;
        if (last < 0 || row - peaks.get(last) > 3) {
            FaintQuarterRestInk.add(budget, peaks, FaintQuarterRestInk.boxInt(budget, row));
        } else if (strength[row] > strength[peaks.get(last)]) {
            FaintQuarterRestInk.set(budget, peaks, last, FaintQuarterRestInk.boxInt(budget, row));
        }
    }

    private static List<Candidate> staffCandidates(
            List<Integer> peaks, int[] strength, int minimumStrength, int height) {
        return staffCandidates(peaks, strength, minimumStrength, height, null);
    }

    private static List<Candidate> staffCandidates(
            List<Integer> peaks,
            int[] strength,
            int minimumStrength,
            int height,
            FaintQuarterRestInk.Budget quarterBudget) {
        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

        List<Candidate> result =
                (quarterBudget == null || quarterBudget.allocate(1, 256)
                        ? new ArrayList<>()
                        : null);
        float maximumGap = Math.max(40f, height * .035f);
        float minimumScore = minimumStrength * 4.5f;
        int peakCount = peaks.size();
        int[] peakRows = null;
        int[] scratchRows = null;
        int[] rangeStarts = null;
        if (quarterBudget == null && peakCount >= 5) {
            peakRows = new int[peakCount];
            for (int i = 0; i < peakCount; i++) peakRows[i] = peaks.get(i);
            scratchRows = new int[5];
            rangeStarts = new int[3];
        }
        {
            if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
            for (int first = 0; first + 4 < peakCount; first++) {
                if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                int top = peakRows == null ? peaks.get(first) : peakRows[first];
                int topStrength = quarterBudget == null ? strength[top] : 0;
                if (scratchRows != null) {
                    scratchRows[0] = top;
                    Arrays.fill(rangeStarts, first + 1);
                }
                {
                    if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                    for (int last = first + 4; last < peakCount; last++) {
                        if (quarterBudget != null) FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                        int bottom = peakRows == null ? peaks.get(last) : peakRows[last];
                        float gap = (bottom - top) / 4f;
                        if (gap < 2.5f) continue;
                        if (gap > maximumGap) break;

                        float tolerance = Math.max(2f, gap * .24f);
                        int[] rows =
                                quarterBudget == null
                                        ? scratchRows
                                        : (quarterBudget.allocate(6, 72)
                                        ? new int[] {top, -1, -1, -1, bottom}
                                        : null);
                        float error = 0f;
                        int totalStrength = quarterBudget == null ? topStrength + strength[bottom] : 0;
                        int previousIndex = first;
                        boolean complete = true;
                        {
                            if (quarterBudget != null)
                                FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                            for (int line = 1; line < 4; line++) {
                                if (quarterBudget != null)
                                    FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                                float expected = top + line * gap;
                                int bestIndex = -1;
                                int bestRow = -1;
                                float bestDistance = Float.MAX_VALUE;
                                {
                                    if (quarterBudget != null)
                                        FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                                    int start = previousIndex + 1;
                                    if (rangeStarts != null) {
                                        int cursor = rangeStarts[line - 1];
                                        // Reuse the lower bound, correcting for float rounding.
                                        while (cursor > first + 1
                                                && expected - peakRows[cursor - 1] <= tolerance)
                                            cursor--;
                                        while (cursor < last && expected - peakRows[cursor] > tolerance)
                                            cursor++;
                                        rangeStarts[line - 1] = cursor;
                                        start = Math.max(start, cursor);
                                    }
                                    for (int index = start; index < last; index++) {
                                        if (quarterBudget != null)
                                            FaintQuarterRestInk.charge(quarterBudget, 1, 0);

                                        int row = peakRows == null ? peaks.get(index) : peakRows[index];
                                        float distance = Math.abs(row - expected);
                                        if (quarterBudget == null && row > expected
                                                && (distance > tolerance || distance > bestDistance))
                                            break;
                                        if (distance > tolerance) continue;
                                        if (distance < bestDistance
                                                || (distance == bestDistance
                                                        && bestIndex >= 0
                                                        && strength[row]
                                                                > strength[bestRow])) {
                                            bestIndex = index;
                                            bestRow = row;
                                            bestDistance = distance;
                                            // Below the float precision boundary, distinct rows cannot tie at zero.
                                            if (quarterBudget == null && distance == 0f
                                                    && row < (1 << 24) - 1) break;
                                        }
                                    }
                                }

                                if (bestIndex < 0 || (quarterBudget == null && last - bestIndex < 4 - line)) {
                                    complete = false;
                                    break;
                                }
                                rows[line] = bestRow;
                                if (quarterBudget == null) totalStrength += strength[bestRow];
                                previousIndex = bestIndex;
                                // Evenly spaced printed lines need not land on integer pixels. Do
                                // not
                                // penalize their unavoidable rounding enough to discard a short
                                // staff
                                // whose five rules each pass the raw-ink threshold.
                                error += Math.max(0f, bestDistance - .5f);
                            }
                        }

                        if (!complete) continue;

                        if (quarterBudget != null) {
                            FaintQuarterRestInk.charge(quarterBudget, 1, 64);
                            for (int row : rows) {
                                FaintQuarterRestInk.charge(quarterBudget, 1, 0);
                                totalStrength += strength[row];
                            }
                        }

                        float score = totalStrength - error * minimumStrength * .75f;
                        if (score < minimumScore) continue;
                        if (quarterBudget == null) {
                            rows[4] = bottom;
                            result.add(new Candidate(rows.clone(), gap, score));
                        } else {
                            FaintQuarterRestInk.add(
                                    quarterBudget,
                                    result,
                                    (quarterBudget.allocate(1, 256)
                                            ? new Candidate(rows, gap, score)
                                            : null));
                        }
                    }
                }
            }
        }

        return result;
    }

}
