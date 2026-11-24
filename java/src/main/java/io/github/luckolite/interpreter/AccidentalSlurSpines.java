// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Bounded optical proof of the two spines of a written natural or sharp. */
final class AccidentalSlurSpines {
    private static final int INK = 145;

    record Spine(int left, int right, int top, int bottom) {}

    private record Run(int top, int bottom) {
        int length() {
            return bottom - top + 1;
        }
    }

    private record Connector(int leftY, int rightY) {}

    private AccidentalSlurSpines() {}

    static List<Spine> prove(
            byte[] gray, int width, float firstX, float ownerX, float ownerY, float gap,
            int accidental) {
        return prove(gray, width, firstX, ownerX, ownerY, gap, accidental, false, 2);
    }

    static List<Spine> proveInterruptedSharp(
            byte[] gray, int width, float firstX, float ownerX, float ownerY, float gap) {
        return prove(gray, width, firstX, ownerX, ownerY, gap, ScoreNoteEvent.ACCIDENTAL_SHARP, true, 2);
    }

    static List<Spine> proveThinNaturalPrefix(
            byte[] gray, int width, float firstX, float ownerX, float ownerY, float gap) {
        return prove(gray, width, firstX, ownerX, ownerY, gap, ScoreNoteEvent.ACCIDENTAL_NATURAL, false, 1);
    }

    private static List<Spine> prove(byte[] gray, int width, float firstX, float ownerX, float ownerY,
            float gap, int accidental, boolean interrupted, int minimumSpineWidth) {
        if (gray == null || width <= 0 || gray.length % width != 0
                || !Float.isFinite(firstX + ownerX + ownerY + gap)
                || gap < 10 || gap > 24 || accidental != 0 && accidental != 1)
            return List.of();
        int height = gray.length / width;
        int left = Math.round(Math.max(firstX + gap * .62f, ownerX - gap * 2.4f));
        int right = Math.round(ownerX - gap * .54f);
        int top = Math.round(ownerY - gap * 2.35f);
        int bottom = Math.round(ownerY + gap * 2.25f);
        if (left < 3 || right >= width - 3 || left >= right || top < 0 || bottom >= height)
            return List.of();
        var spines = new ArrayList<Spine>();
        for (int x = left; x <= right;) {
            Run column = interrupted ? interruptedSpine(gray, width, x, top, bottom, ownerY, gap)
                    : longestSpine(gray, width, x, top, bottom, ownerY, gap);
            if (column == null) {
                x++;
                continue;
            }
            int first = x;
            Run best = column;
            while (++x <= right) {
                column = interrupted ? interruptedSpine(gray, width, x, top, bottom, ownerY, gap)
                    : longestSpine(gray, width, x, top, bottom, ownerY, gap);
                if (column == null) break;
                if (column.length() > best.length()) best = column;
            }
            if (x - first < minimumSpineWidth || x - first > Math.round(gap * .33f)) continue;
            spines.add(new Spine(first, x - 1, best.top(), best.bottom()));
        }
        List<Spine> result = List.of();
        for (int i = 0; i + 1 < spines.size(); i++) {
            Spine a = spines.get(i);
            Spine b = spines.get(i + 1);
            float spacing = (b.left() + b.right() - a.left() - a.right()) * .5f;
            float ownerDistance = ownerX - (b.left() + b.right()) * .5f;
            if (spacing < gap * .42f || spacing > gap * .76f
                    || ownerDistance < gap * .78f || ownerDistance > gap * 1.60f
                    || Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top()) < gap * 1.12f
                    || !endpointPattern(a, b, gap, accidental)
                    || !pairedConnectors(gray, width, a, b, ownerY, gap, accidental == 1))
                continue;
            if (!result.isEmpty()) return List.of();
            result = List.of(a, b);
        }
        return result;
    }

    private static Run longestSpine(
            byte[] gray, int width, int x, int top, int bottom, float ownerY, float gap) {
        Run best = null;
        int minRows = Math.round(gap * 1.65f);
        int first = top;
        for (int y = top; y <= bottom + 1; y++) {
            if (y <= bottom && ink(gray, width, x, y)) continue;
            if (y - first >= minRows && first <= ownerY - gap * .38f
                    && y - 1 >= ownerY + gap * .36f
                    && (best == null || y - first > best.length()))
                best = new Run(first, y - 1);
            first = y + 1;
        }
        return best;
    }

    private static Run interruptedSpine(
            byte[] gray, int width, int x, int top, int bottom, float ownerY, float gap) {
        Run best = null;
        int previousStart = -1, previousEnd = -1;
        for (int y = top; y <= bottom;) {
            int start = y;
            while (y <= bottom && (gray[y * width + x] & 255) < 190) y++;
            int end = y - 1;
            if (end - start >= gap * 2.35f && end - start <= gap * 3.55f
                    && start <= ownerY - .65f * gap && end >= ownerY + .65f * gap) {
                var continuous = new Run(start, end);
                if (best == null || continuous.length() > best.length()) best = continuous;
            }
            if (end - start + 1 >= gap * .70f) {
                if (previousStart >= 0 && start - previousEnd <= gap * .60f
                        && previousStart <= ownerY - .65f * gap && end >= ownerY + .65f * gap
                        && end - previousStart >= gap * 2.35f && end - previousStart <= gap * 3.55f) {
                    var pair = new Run(previousStart, end);
                    if (best == null || pair.length() > best.length()) best = pair;
                }
                previousStart = start;
                previousEnd = end;
            }
            y++;
        }
        return best != null ? best : longestSpine(gray, width, x, top, bottom, ownerY, gap);
    }

    private static boolean endpointPattern(Spine a, Spine b, float gap, int accidental) {
        int topShift = b.top() - a.top();
        int bottomShift = b.bottom() - a.bottom();
        if (accidental == 0)
            return topShift >= gap * .35f && topShift <= gap * 1.05f
                    && bottomShift >= gap * .30f && bottomShift <= gap * 1.02f;
        return topShift <= gap * .23f && topShift >= -gap * .80f
                && Math.abs(bottomShift) <= gap * .32f;
    }

    private static boolean pairedConnectors(
            byte[] gray, int width, Spine a, Spine b, float ownerY, float gap, boolean sharp) {
        int left = a.right() + 1;
        int right = b.left() - 1;
        if (right - left < 2) return false;
        var upper = connectors(gray, width, a, b, left, right,
                Math.round(ownerY - gap * .93f), Math.round(ownerY - gap * .25f), sharp);
        var lower = connectors(gray, width, a, b, left, right,
                Math.round(ownerY + gap * .24f), Math.round(ownerY + gap * .80f), sharp);
        for (Connector u : upper) {
            for (Connector l : lower) {
                int separation = l.leftY() - u.leftY();
                if (separation < gap * .73f || separation > gap * 1.60f
                        || Math.abs((l.rightY() - l.leftY()) - (u.rightY() - u.leftY())) > 3)
                    continue;
                int openings = 0;
                int midpoint = (u.leftY() + u.rightY() + l.leftY() + l.rightY()) / 4;
                for (int x = left; x <= right; x++)
                    if (!ink(gray, width, x, midpoint) && !ink(gray, width, x, midpoint + 1))
                        openings++;
                if (openings >= Math.max(2, (right - left + 1) * 2 / 3)) return true;
            }
        }
        return false;
    }

    private static List<Connector> connectors(
            byte[] gray, int width, Spine a, Spine b,
            int left, int right, int top, int bottom, boolean sharp) {
        var found = new ArrayList<Connector>();
        for (int y = top; y <= bottom; y++) {
            for (int rise = 0; rise <= 4; rise++) {
                if (!covers(gray, width, left, right, y, rise)) continue;
                if (sharp) {
                    int atLeft = a.left() - 1, atRight = b.right() + 1;
                    float incline = rise / (float) (right - left);
                    int predictedLeft = Math.round(y + incline * (left - atLeft));
                    int predictedRight = Math.round(y - incline * (atRight - left));
                    if (!nearInk(gray, width, atLeft, predictedLeft)
                            || !nearInk(gray, width, atRight, predictedRight)) continue;
                }
                found.add(new Connector(y, y - rise));
            }
        }
        return found;
    }

    private static boolean covers(byte[] gray, int width, int left, int right, int y, int rise) {
        for (int x = left; x <= right; x++) {
            int predicted = Math.round(y - rise * (x - left) / (float) (right - left));
            if (!nearInk(gray, width, x, predicted)) return false;
        }
        return true;
    }

    private static boolean nearInk(byte[] gray, int width, int x, int y) {
        return ink(gray, width, x, y) || ink(gray, width, x, y - 1)
                || ink(gray, width, x, y + 1);
    }

    private static boolean ink(byte[] gray, int width, int x, int y) {
        return (gray[y * width + x] & 255) <= INK;
    }
}
