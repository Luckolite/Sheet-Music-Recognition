// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

/** Local dot evidence for a triplet whose owner and following attack are already proved. */
final class WrappedTripletDotInk {
    private WrappedTripletDotInk() {}

    record Center(int x, int y) {}

    static Center uniqueCore(byte[] gray, int width, int height,
            float headX, float headY, float nextX, float gap) {
        return uniqueCore(gray, width, height, headX, headY, nextX, gap, 1.44f);
    }

    static Center dottedHalfCore(byte[] gray, int width, int height,
            float headX, float headY, float closingX, float gap) {
        return uniqueCore(gray, width, height, headX, headY, closingX, gap, 1.65f);
    }

    private static Center uniqueCore(byte[] gray, int width, int height,
            float headX, float headY, float nextX, float gap, float horizontalReach) {
        if (gray == null || gray.length != (long) width * height
                || !Float.isFinite(headX + headY + nextX + gap) || gap < 10 || gap > 30) return null;
        int left = Math.round(headX + gap * .87f);
        int right = Math.min(Math.round(headX + gap * horizontalReach), Math.round(nextX - gap * .58f));
        int top = Math.round(headY - gap * .70f);
        int bottom = Math.round(headY + gap * .30f);
        int reach = Math.round(gap * .68f);
        if (left <= reach || right >= width - reach || top <= reach || bottom >= height - reach)
            return null;

        int coreRadius = Math.round(gap * .125f);
        int faceStart = Math.round(gap * .31f), faceEnd = Math.round(gap * .37f);
        int minimumCore = (int) Math.ceil(Math.pow(2 * coreRadius + 1, 2) * .76);
        int minimumCross = (int) Math.ceil((2 * coreRadius + 1) * .8);
        int faceMaximum = (int) Math.floor((faceEnd - faceStart + 1) * (2 * coreRadius + 1) * .2);
        int sameGlyph = Math.round(gap * .27f);
        Center first = null;
        Center best = null;
        int bestScore = -1;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int core = count(gray, width, x - coreRadius, x + coreRadius,
                        y - coreRadius, y + coreRadius, 105);
                if (core < minimumCore
                        || count(gray, width, x - coreRadius, x + coreRadius, y, y, 105) < minimumCross
                        || count(gray, width, x, x, y - coreRadius, y + coreRadius, 105) < minimumCross)
                    continue;
                int rightInk = count(gray, width, x + faceStart, x + faceEnd,
                        y - coreRadius, y + coreRadius, 155);
                if (rightInk > faceMaximum) continue;
                int leftInk = count(gray, width, x - faceEnd, x - faceStart,
                        y - coreRadius, y + coreRadius, 155);
                int lowerInk = count(gray, width, x - coreRadius, x + coreRadius,
                        y + faceStart, y + faceEnd, 155);
                int upperInk = count(gray, width, x - coreRadius, x + coreRadius,
                        y - faceEnd, y - faceStart, 155);
                int attachedFaces = (leftInk > faceMaximum ? 1 : 0)
                        + (lowerInk > faceMaximum ? 1 : 0) + (upperInk > faceMaximum ? 1 : 0);
                if (attachedFaces > 1) continue;
                if (lowerInk > faceMaximum && !ruleBeside(gray, width, x, y, gap, 1)
                        || upperInk > faceMaximum && !ruleBeside(gray, width, x, y, gap, -1)) continue;
                if (first == null) first = new Center(x, y);
                if (Math.abs(x - first.x) > sameGlyph || Math.abs(y - first.y) > sameGlyph)
                    return null;
                int score = core * 8 - rightInk - leftInk - lowerInk - upperInk;
                if (score > bestScore) {
                    best = new Center(x, y);
                    bestScore = score;
                }
            }
        }
        return best;
    }

    /** A staff/slur crossing can compact the foot of the first of two printed flats.
     * Require the same pair of spines, clear middle, and tapering right-facing bowls. */
    static boolean pairedFlatOwns(byte[] gray, int width, int height, Center dot, float gap) {
        int x = dot.x(), y = dot.y();
        int top = y - Math.round(gap * 1.75f),
                taper = y + Math.round(gap * .15f),
                middle = y - Math.round(gap * .42f);
        int spineRadius = Math.round(gap * .17f);
        if (x - spineRadius < 0
                || x + Math.round(gap * 2.1f) >= width
                || top < 0
                || taper + 1 >= height) return false;
        for (int left = x - spineRadius; left <= x; left++) {
            if (!flatSpine(gray, width, left, top, y)) continue;
            for (int right = left + Math.round(gap * .98f);
                    right <= left + Math.round(gap * 1.18f);
                    right++) {
                if (!flatSpine(gray, width, right, top, y)
                        || !flatBowl(gray, width, left, middle, taper, gap)
                        || !flatBowl(gray, width, right, middle, taper, gap)) continue;
                int from = y - Math.round(gap * 1.05f), to = y - Math.round(gap * .28f);
                int space = right - Math.round(gap * .21f), clear = 0;
                for (int row = from; row <= to; row++)
                    if (count(gray, width, space, right - 1, row, row, 155)
                            <= (right - space) * .4f) clear++;
                if (clear < (to - from + 1) * .7f) continue;
                if (count(gray, width, left, x, y, y, 155) == x - left + 1) return true;
            }
        }
        return false;
    }

    static boolean singleFlatOwns(
            byte[] gray, int width, int height, Center core, float noteX, float noteY, float gap) {
        return singleFlatOwns(gray, width, height, core, noteX, noteY, gap, false);
    }

    static boolean compactSingleFlatOwns(byte[] gray, int width, int height,
            Center core, float noteX, float noteY, float gap) {
        return singleFlatOwns(gray, width, height, core, noteX, noteY, gap, true);
    }

    private static boolean singleFlatOwns(byte[] gray, int width, int height,
            Center core, float noteX, float noteY, float gap, boolean compactBowl) {
        float minimumBefore = compactBowl ? 1.2f : 1.4f;
        float minimumBelow = compactBowl ? .20f : .45f;
        float before = noteX - core.x(), below = core.y() - noteY;
        if (before < gap * minimumBefore || before > gap * 1.95f
                || below < gap * minimumBelow || below > gap * .78f) return false;
        int left = core.x() - Math.round(gap * .49f);
        int right = core.x() + Math.round(gap * .45f);
        int top = core.y() - Math.round(gap * 1.96f);
        int bottom = core.y() + Math.round(gap * (compactBowl ? .20f : .49f));
        if (!PrintedFlatGlyph.matches(gray, width, height, left, top, right, bottom, gap)) return false;
        int spineTop = core.y() - Math.round(gap * 1.75f);
        int contact = compactBowl ? Math.round(gap * .13f) : 0;
        for (int spine = left; spine < core.x(); spine++) {
            if (!flatSpine(gray, width, spine, spineTop, core.y())) continue;
            for (int row = core.y(); row <= core.y() + contact; row++) {
                if (count(gray, width, core.x(), core.x(), core.y(), row, 155) != row - core.y() + 1)
                    break;
                if (count(gray, width, spine, core.x(), row, row, 155) == core.x() - spine + 1
                        && (!compactBowl || (gray[row * width + right] & 255) >= 155)) return true;
            }
        }
        return false;
    }

    /** A low flat bowl can reach the dot window while its full spine remains optically owned. */
    static boolean labelledFlatOwns(byte[] labels, byte[] gray, int width, int height,
            Center core, float noteX, float noteY, float gap) {
        if (core == null || labels == null || gray == null
                || labels.length != gray.length || gray.length != (long) width * height
                || core.x() < 0 || core.x() >= width || core.y() < 0 || core.y() >= height) return false;
        float before = noteX - core.x(), below = core.y() - noteY;
        if (before < gap * 1.38f || before > gap * 1.95f || below < gap * .40f || below > gap * .78f
                || labels[core.y() * width + core.x()] != OmrMeasurePostProcessor.CLEF_OR_KEY
                || !PrintedFlatGlyph.matchesOwnedInk(labels, gray, width, height, noteX, noteY, gap))
            return false;
        int left = core.x() - Math.round(gap * .46f);
        int top = core.y() - Math.round(gap * 1.75f);
        for (int spine = left; spine + 1 < core.x(); spine++) {
            int rows = 0;
            for (int y = top; y <= core.y(); y++)
                if (ownedInk(labels, gray, width, spine, y) && ownedInk(labels, gray, width, spine + 1, y))
                    rows++;
            if (rows < (core.y() - top + 1) * .94f) continue;
            int joined = 0;
            for (int x = spine; x <= core.x(); x++) if (ownedInk(labels, gray, width, x, core.y())) joined++;
            if (joined == core.x() - spine + 1) return true;
        }
        return false;
    }

    /** Prove that the exact core belongs to a nearby single flat, excluding staff contact. */
    static boolean connectedFlatOwns(byte[] labels, byte[] gray, int width, int height,
            Center core, float noteX, float noteY, float gap) {
        float before = noteX - core.x(), below = core.y() - noteY;
        if (before < gap * 1.35f || before > gap * 2.1f || below < gap * .25f || below > gap * .8f
                || labels[core.y() * width + core.x()] != OmrMeasurePostProcessor.CLEF_OR_KEY) return false;
        if (compactSingleFlatOwns(gray, width, height, core, noteX, noteY, gap)
                && PrintedFlatGlyph.matchesOwnedInk(labels, gray, width, height, noteX, noteY, gap)) return true;

        int left = Math.max(0, Math.round(noteX - gap * 3.1f));
        int right = Math.min(width - 1, Math.round(noteX - gap * .65f));
        int top = Math.max(0, Math.round(noteY - gap * 2.7f));
        int bottom = Math.min(height - 1, Math.round(noteY + gap * 1.5f));
        int w = right - left + 1, h = bottom - top + 1;
        var ink = new byte[w * h];
        java.util.Arrays.fill(ink, (byte) 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = (top + y) * width + left + x;
                if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY) ink[y * w + x] = gray[at];
            }
        }
        int seed = (core.y() - top) * w + core.x() - left;
        var parts = RawNumeralComponents.capture(ink, w, h, 0, 0, w - 1, h - 1, 165, () -> {}, () -> {});
        for (var part : parts.components()) {
            for (int i = 0; i < part.size(); i++) {
                if (part.pixel(i) != seed) continue;
                java.util.Arrays.fill(ink, (byte) 255);
                for (int j = 0; j < part.size(); j++) ink[part.pixel(j)] = 0;
                return PrintedFlatGlyph.matches(
                        ink, w, h, part.left, part.top, part.right, part.bottom, gap);
            }
        }
        return false;
    }

    private static boolean ownedInk(byte[] labels, byte[] gray, int width, int x, int y) {
        int offset = y * width + x;
        return labels[offset] == OmrMeasurePostProcessor.CLEF_OR_KEY && (gray[offset] & 255) < 155;
    }

    /** Separate contacting flat bowls while keeping the original core in the left glyph. */
    static boolean splitFlatPairOwns(byte[] labels, byte[] gray, int width, int height,
            Center core, float noteX, float noteY, float gap) {
        float before = noteX - core.x(), below = core.y() - noteY;
        if (before < gap * 1.75f || before > gap * 2.6f || below < 0 || below > gap * .95f
                || labels[core.y() * width + core.x()] != OmrMeasurePostProcessor.CLEF_OR_KEY) return false;
        int left = Math.max(0, Math.round(noteX - gap * 3.75f));
        int right = Math.min(width - 1, Math.round(noteX - gap * .42f));
        int top = Math.max(0, Math.round(noteY - gap * 2.5f));
        int bottom = Math.min(height - 1, Math.round(noteY + gap * 1.65f));
        int w = right - left + 1, h = bottom - top + 1;
        var ink = new byte[w * h];
        java.util.Arrays.fill(ink, (byte) 255);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = (top + y) * width + left + x;
                if (labels[at] == OmrMeasurePostProcessor.CLEF_OR_KEY) ink[y * w + x] = gray[at];
            }
        }
        int seedX = core.x() - left, seedPixel = (core.y() - top) * w + seedX;
        int firstCut = seedX + Math.round(gap * .24f);
        int lastCut = Math.min(w - 2, seedX + Math.round(gap * .93f));
        var isolated = new byte[w * h];
        for (int cut = firstCut; cut <= lastCut; cut++) {
            var leftComponents =
                    RawNumeralComponents.capture(ink, w, h, 0, 0, cut - 1, h - 1, 165, () -> {}, () -> {});
            for (var leftPart : leftComponents.components()) {
                boolean containsCore = false;
                for (int i = 0; i < leftPart.size(); i++) containsCore |= leftPart.pixel(i) == seedPixel;
                if (!containsCore || !isolatedFlat(isolated, w, h, leftPart, gap)) continue;
                var rightComponents =
                        RawNumeralComponents.capture(ink, w, h, cut, 0, w - 1, h - 1, 165, () -> {}, () -> {});
                for (var rightPart : rightComponents.components()) {
                    float spacing = rightPart.left - leftPart.left;
                    if (spacing >= gap * .77f && spacing <= gap * 1.25f
                            && isolatedFlat(isolated, w, h, rightPart, gap)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isolatedFlat(byte[] ink, int width, int height, RawNumeralComponents.Component part,
            float gap) {
        java.util.Arrays.fill(ink, (byte) 255);
        for (int i = 0; i < part.size(); i++) ink[part.pixel(i)] = 0;
        return PrintedFlatGlyph.matches(ink, width, height, part.left, part.top, part.right, part.bottom, gap);
    }

    private static boolean flatSpine(byte[] gray, int width, int x, int top, int bottom) {
        return count(gray, width, x, x + 1, top, bottom, 155) >= 2 * (bottom - top + 1) * .96f;
    }

    private static boolean flatBowl(
            byte[] gray, int width, int x, int middle, int taper, float gap) {
        int bowlLeft = x + Math.round(gap * .48f), bowlRight = x + Math.round(gap * .74f) - 1;
        if (count(gray, width, bowlLeft, bowlRight, middle - 5, middle - 2, 155)
                < (bowlRight - bowlLeft + 1) * 4 * .55f) return false;
        int tailLeft = x + Math.round(gap * .31f), tailRight = x + Math.round(gap * .79f) - 1;
        return count(gray, width, tailLeft, tailRight, taper - 1, taper + 1, 155)
                <= (tailRight - tailLeft + 1) * 3 * .10f;
    }

    // A vertical contact must meet a short staff/ledger rail on both sides of the dot.
    private static boolean ruleBeside(byte[] gray, int width, int x, int y, float gap, int direction) {
        int far = Math.round(gap * .68f), near = Math.round(gap * .37f);
        int minimum = (int) Math.ceil((far - near + 1) * .65);
        for (int step = Math.round(gap * .125f); step <= Math.round(gap * .62f); step++) {
            int row = y + direction * step;
            if (count(gray, width, x - far, x - near, row, row, 155) >= minimum
                    && count(gray, width, x + near, x + far, row, row, 155) >= minimum)
                return true;
        }
        return false;
    }

    private static int count(byte[] gray, int width,
            int left, int right, int top, int bottom, int cutoff) {
        int ink = 0;
        for (int y = top; y <= bottom; y++)
            for (int x = left; x <= right; x++)
                if ((gray[y * width + x] & 255) < cutoff) ink++;
        return ink;
    }
}
