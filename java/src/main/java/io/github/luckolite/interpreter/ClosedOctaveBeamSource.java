// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Captures literal beams in a physically closed dotted octave run after its false head is proved. */
final class ClosedOctaveBeamSource {
    private ClosedOctaveBeamSource() {}

    record Attack(
            int upperOwner,
            int lowerOwner,
            float upperX,
            float upperY,
            float lowerX,
            float lowerY) {}

    private record Axis(int x, int tip, int left, int right) {}

    private record Rails(Axis left, Axis right, int leadingY, int trailingY, int separation) {
        int primary(int x) {
            return Math.round(
                    leadingY + (trailingY - leadingY) * (x - left.x) / (float) (right.x - left.x));
        }
    }

    private record Ink(byte[] gray, int width, int height, boolean inverted) {
        Ink(byte[] gray, int width, int height) {
            this(gray, width, height, false);
        }

        boolean dark(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return false;
            int row = inverted ? height - 1 - y : y;
            return (gray[row * width + x] & 255) < 165;
        }

        boolean core(int x, int y, int radius) {
            for (int dy = -radius; dy <= radius; dy++) if (!dark(x, y + dy)) return false;
            return true;
        }
    }

    static ScoreBeamSource capture(
            ScoreBeamSource source,
            int page,
            byte[] gray,
            int width,
            int height,
            float gap,
            List<Attack> attacks) {
        if (page < 0 || attacks.size() != 16) return source;
        return provesRibbons(gray, width, height, gap, attacks, null)
                ? projectDottedRibbons(source, page, attacks)
                : source;
    }

    /** Admits only complete four-stem groups in an independently separated printed bar. */
    static ScoreBeamSource captureSeparateDottedOctaveGroups(
            ScoreBeamSource source, int page, byte[] gray, byte[] original,
            int width, int height, float gap, List<Attack> attacks) {
        if (page < 0 || attacks.size() != 16 || gap < 10 || gap > 27
                || gray == null || original == null || gray.length != (long) width * height
                || gray.length != original.length) return source;
        var ink = new Ink(gray, width, height);
        var raw = new Ink(original, width, height);
        var budget = new PrintedBeamShafts.Budget(160_000);
        for (int cut = 4; cut < attacks.size(); cut += 4) {
            var left = boundaryAxis(ink, attacks.get(cut - 1), gap, budget);
            var right = boundaryAxis(ink, attacks.get(cut), gap, budget);
            if (left == null || right == null
                    || !openBetweenRibbons(ink, left, right, gap)
                    || original != gray && !openBetweenRibbons(raw, left, right, gap)) return source;
        }
        if (budget.refused()) return source;
        var result = source;
        for (int index = 0; index < attacks.size(); index += 4) {
            var group = attacks.subList(index, index + 4);
            if (provesRibbons(gray, width, height, gap, group, null)
                    && (original == gray || provesRibbons(original, width, height, gap, group, null)))
                result = projectDottedRibbons(result, page, group);
        }
        return result;
    }

    private static Axis boundaryAxis(
            Ink ink, Attack attack, float gap, PrintedBeamShafts.Budget budget) {
        var shafts = PrintedBeamShafts.at(
                ink.gray, ink.width, ink.height, attack.lowerX, attack.lowerY, gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != 1) return null;
        var shaft = shafts.get(0);
        return new Axis((shaft.left() + shaft.right()) / 2, shaft.endY(), shaft.left(), shaft.right());
    }

    private static boolean openBetweenRibbons(Ink ink, Axis left, Axis right, float gap) {
        if (right.left - left.right < gap * 1.4f) return false;
        int center = (left.right + right.left) / 2;
        int margin = Math.round(gap * .18f);
        int drift = Math.round((right.x - left.x) * .20f);
        int top = Math.max(left.tip, right.tip) - Math.round(gap * 2.30f) - drift;
        int bottom = Math.min(left.tip, right.tip) + Math.round(gap * .42f) + drift;
        if (top < 0 || bottom >= ink.height || bottom - top < gap * .70f) return false;
        for (int x = center - margin; x <= center + margin; x++)
            for (int y = top + 1; y < bottom; y++)
                if (ink.core(x, y, 1)
                        && (ink.dark(x, y - 2) || ink.dark(x, y + 2))) return false;
        return true;
    }

    /** Proves each detached group from a single physical shaft shared by each dyad. */
    static ScoreBeamSource captureDetachedDottedDyads(
            ScoreBeamSource source, int page, byte[] gray, byte[] original,
            int width, int height, float gap, List<Attack> attacks, List<Integer> directions) {
        if (page < 0 || attacks.size() != 16 || directions.size() != attacks.size()
                || gap < 10 || gap > 27 || gray == null || original == null
                || gray.length != (long) width * height || gray.length != original.length)
            return source;
        var printed = source;
        for (int start = 0; start < attacks.size(); start += 4) {
            var selected = attacks.subList(start, start + 4);
            int direction = directions.get(start);
            if (direction != 1 && direction != -1
                    || directions.subList(start, start + 4).stream().anyMatch(d -> d != direction))
                continue;
            if (provesDetachedDyadRibbon(gray, width, height, gap, selected, direction, page)
                    && (original == gray || provesDetachedDyadRibbon(
                            original, width, height, gap, selected, direction, page)))
                printed = projectDottedRibbons(printed, page, selected);
        }
        return printed;
    }

    static ScoreBeamSource captureMixedDottedSixthRibbons(
            ScoreBeamSource source, int page, byte[] gray, byte[] original,
            int width, int height, float gap, List<Attack> attacks, List<Integer> directions) {
        if (page < 0 || attacks.size() != 16 || directions.size() != 16
                || directions.subList(0, 8).stream().anyMatch(direction -> direction != -1)
                || directions.subList(8, 16).stream().anyMatch(direction -> direction != 1))
            return source;
        for (int start = 0; start < attacks.size(); start += 4) {
            var group = attacks.subList(start, start + 4);
            int direction = directions.get(start);
            if (!provesMixedSixthGroup(gray, width, height, gap, page, group, direction, start)
                    || original != gray && !provesMixedSixthGroup(
                            original, width, height, gap, page, group, direction, start))
                return source;
        }
        return projectDottedRibbons(source, page, attacks);
    }

    private static boolean provesMixedSixthGroup(byte[] gray, int width, int height, float gap, int page,
            List<Attack> attacks, int direction, int start) {
        return provesDetachedDyadRibbon(gray, width, height, gap, attacks, direction, page)
                || start == 0 && provesDetachedDyadRibbon(gray, width, height, gap, attacks, direction, page, true);
    }

    static ScoreBeamSource captureStaffRuleHookOctaves(
            ScoreBeamSource source, int page, byte[] gray, byte[] original,
            int width, int height, float gap, List<Attack> attacks, int group, float x, float y) {
        var claimed = captureDetachedDottedDyads(source, page, gray, original,
                width, height, gap, attacks, java.util.Collections.nCopies(attacks.size(), 1));
        var selected = attacks.subList(group, group + 4);
        float followingX = attacks.get(group + 4).upperX();
        if (!OwnedTertiaryBeamlets.provesStaffRuleHookAliasRibbon(
                    gray, width, height, gap, selected, followingX, x, y)
                || !OwnedTertiaryBeamlets.provesStaffRuleHookAliasRibbon(
                    original, width, height, gap, selected, followingX, x, y)) return source;
        return projectDottedRibbons(claimed, page, selected);
    }

    private static boolean provesDetachedDyadRibbon(
            byte[] gray, int width, int height, float gap,
            List<Attack> attacks, int direction, int page) {
        return provesDetachedDyadRibbon(gray, width, height, gap, attacks, direction, page, false);
    }

    private static boolean provesDetachedDyadRibbon(
            byte[] gray, int width, int height, float gap,
            List<Attack> attacks, int direction, int page, boolean compressedOpening) {
        var budget = new PrintedBeamShafts.Budget(1_200_000);
        var frame = new PrintedBeamShaftPixels.Frame(attacks, gray, width, height, page, budget::take, true);
        var axes = new ArrayList<Axis>(4);
        for (var attack : attacks) {
            var axis = sharedDyadAxis(gray, width, height, gap, attack, direction, budget, frame);
            if (axis == null || budget.refused()) return false;
            axes.add(axis);
        }
        return closedRibbon(new Ink(gray, width, height, direction < 0), axes, gap, null, true, compressedOpening);
    }

    private static Axis sharedDyadAxis(
            byte[] gray, int width, int height, float gap, Attack attack, int direction,
            PrintedBeamShafts.Budget budget, PrintedBeamShaftPixels.Frame frame) {
        var upper = PrintedBeamShafts.atAllVisited(
                gray, width, height, attack.upperX, attack.upperY, gap, budget, frame);
        var lower = PrintedBeamShafts.atAllVisited(
                gray, width, height, attack.lowerX, attack.lowerY, gap, budget, frame);
        // Each shaft must independently connect its head to the same beam-side cap.
        Axis answer = null;
        for (var a : upper) {
            for (var b : lower) {
                if (a.direction() != direction || b.direction() != direction
                        || Math.abs(a.endY() - b.endY()) > gap * .42f) continue;
                int left = Math.max(a.left(), b.left()), right = Math.min(a.right(), b.right());
                if (left > right) continue;
                int x = (left + right + 1) / 2;
                int tip = direction > 0 ? Math.min(a.endY(), b.endY())
                        : height - 1 - Math.max(a.endY(), b.endY());
                var witness = new Axis(x, tip, left, right);
                if (answer != null && !answer.equals(witness)) return null;
                answer = witness;
            }
        }
        return answer;
    }

    /** A bounded four-column dotted group reaching a proved closing measure edge. */
    static ScoreBeamSource captureClosingDottedDyads(
            ScoreBeamSource source,
            int page,
            byte[] gray,
            int width,
            int height,
            float gap,
            Attack before,
            List<Attack> ending) {
        if (page < 0
                || ending.size() != 4
                || !provesRibbons(gray, width, height, gap, ending, null)) return source;
        var budget = new PrintedBeamShafts.Budget(80_000);
        var first = ending.get(0);
        var preceding =
                PrintedBeamShafts.at(
                        gray, width, height, before.lowerX, before.lowerY, gap, budget);
        var beginning =
                PrintedBeamShafts.at(gray, width, height, first.lowerX, first.lowerY, gap, budget);
        if (budget.refused() || preceding.size() != 1 || beginning.size() != 1) return source;
        var a = preceding.get(0);
        var b = beginning.get(0);
        if (a.direction() != 1
                || b.direction() != 1
                || b.left() - a.right() < 1.3f * gap
                || b.left() - a.right() > 3.8f * gap
                || !WrappedEighthTriplets.matchesRailBridge(gray, width, height, a, b, gap, false))
            return source;
        return projectDottedRibbons(source, page, ending);
    }

    static ScoreBeamSource captureOpeningStaffCrossingDyads(
            ScoreBeamSource source,
            int page,
            byte[] gray,
            int width,
            int height,
            float gap,
            float staffTop,
            float staffGap,
            List<Attack> ending,
            float followingX) {
        return page >= 0
                        && OwnedTertiaryBeamlets.provesOpeningStaffCrossingRibbon(
                                gray, width, height, gap, staffTop, staffGap, ending, followingX)
                ? projectDottedRibbons(source, page, ending)
                : source;
    }

    record Fragment(float x, float y) {}

    static boolean provesTripleClock(
            byte[] gray,
            int width,
            int height,
            float gap,
            List<Attack> attacks,
            Fragment fragment) {
        return fragment != null
                && attacks.size() == 12
                && provesRibbons(gray, width, height, gap, attacks, fragment);
    }

    private static boolean provesRibbons(
            byte[] gray,
            int width,
            int height,
            float gap,
            List<Attack> attacks,
            Fragment fragment) {
        if (gap < 10 || gap > 27 || gray == null || gray.length != (long) width * height)
            return false;
        var ink = new Ink(gray, width, height);
        var budget = new PrintedBeamShafts.Budget(160_000);
        var axes = new ArrayList<Axis>(attacks.size());
        for (var attack : attacks) {
            var axis = sharedAxis(ink, attack, gap, budget);
            if (axis == null) return false;
            axes.add(axis);
        }
        if (fragment != null
                && !sharesReverseShaft(ink, attacks.get(1), axes, fragment, gap, budget))
            return false;
        if (budget.refused()) return false;
        for (int i = 0; i < axes.size(); i += 4)
            if (!closedRibbon(ink, axes.subList(i, i + 4), gap, i == 0 ? fragment : null))
                return false;
        return true;
    }

    private static boolean sharesReverseShaft(
            Ink ink,
            Attack attack,
            List<Axis> axes,
            Fragment fragment,
            float gap,
            PrintedBeamShafts.Budget budget) {
        var first = axes.get(0);
        var second = axes.get(1);
        if (fragment.x < first.x + .7f * gap
                || fragment.x > second.x - .58f * gap
                || fragment.y - attack.lowerY < 2 * gap) return false;
        var shafts =
                PrintedBeamShafts.at(
                        ink.gray, ink.width, ink.height, fragment.x, fragment.y, gap, budget);
        if (shafts.size() != 1) return false;
        var shaft = shafts.get(0);
        return shaft.direction() == -1
                && shaft.left() >= second.left
                && shaft.right() <= second.right
                && shaft.endY() <= attack.lowerY - gap * .65f;
    }

    private static Axis sharedAxis(
            Ink ink, Attack attack, float gap, PrintedBeamShafts.Budget budget) {
        var shafts =
                PrintedBeamShafts.at(
                        ink.gray, ink.width, ink.height, attack.lowerX, attack.lowerY, gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != 1) return null;
        var lower = shafts.get(0);
        int first = Math.round(attack.upperY + gap * .24f);
        int last = Math.round(attack.lowerY - gap * .30f);
        if (last - first < 2 * gap
                || first < 0
                || last >= ink.height
                || lower.left() < 2 * gap
                || lower.right() >= ink.width - gap
                || lower.endY() < 4 * gap
                || lower.endY() >= ink.height - gap) return null;
        boolean owned = false;
        for (int x = lower.left(); x <= lower.right(); x++) {
            if (attack.upperX - x < .38f * gap || attack.upperX - x > .92f * gap) continue;
            int inkRows = 0;
            for (int y = first; y <= last; y++) if (ink.dark(x, y)) inkRows++;
            if (inkRows >= .90f * (last - first + 1)) owned = true;
        }
        return owned
                ? new Axis(
                        Math.round((lower.left() + lower.right()) * .5f),
                        lower.endY(),
                        lower.left(),
                        lower.right())
                : null;
    }

    private static boolean closedRibbon(Ink ink, List<Axis> axes, float gap, Fragment fragment) {
        return closedRibbon(ink, axes, gap, fragment, false);
    }

    private static boolean closedRibbon(
            Ink ink, List<Axis> axes, float gap, Fragment fragment, boolean detached) {
        return closedRibbon(ink, axes, gap, fragment, detached, false);
    }

    private static boolean closedRibbon(Ink ink, List<Axis> axes, float gap, Fragment fragment,
            boolean detached, boolean compressedOpening) {
        int radius = Math.max(1, Math.round(.12f * gap));
        var first = axes.get(0);
        var last = axes.get(3);
        int width = last.x - first.x;
        int margin = Math.round(.29f * gap);
        for (int i = 1; i < axes.size(); i++)
            if (axes.get(i).x - axes.get(i - 1).x < 2 * margin + 5) return false;
        if (width > 13 * gap) return false;

        int reach = Math.round(.86f * gap), extra = Math.round(.12f * gap);
        for (int a = first.tip - reach; a <= first.tip + extra; a++)
            for (int b = last.tip - reach; b <= last.tip + extra; b++) {
                if (Math.abs(b - a) > .36f * width) continue;
                for (int separation = Math.round(.61f * gap);
                        separation <= Math.round(.92f * gap);
                        separation++) {
                    var rails = new Rails(first, last, a, b, separation);
                    if ((fragment == null
                                    || fragmentInsideFusedRails(ink, rails, fragment, gap, radius))
                            && coversBridges(ink, rails, axes, radius, margin, fragment != null, compressedOpening)
                            && shortBackwardHook(ink, rails, axes.get(1).x, gap, radius)
                            && shortBackwardHook(ink, rails, axes.get(3).x, gap, radius)
                            && (!detached || terminatesAtOwnStem(ink, rails, first, -1, gap, radius)
                                    && terminatesAtOwnStem(ink, rails, last, 1, gap, radius)))
                        return true;
                }
            }
        return false;
    }

    private static boolean terminatesAtOwnStem(
            Ink ink, Rails rails, Axis shaft, int side, float gap, int radius) {
        int edge = side < 0 ? shaft.left : shaft.right;
        int near = Math.round(.22f * gap), far = Math.round(.52f * gap);
        int tolerance = Math.round(.18f * gap);
        if (edge + side * far < 0 || edge + side * far >= ink.width) return false;
        for (int rail : new int[] {0, rails.separation}) {
            for (int dy = -tolerance; dy <= tolerance; dy++) {
                int count = 0;
                for (int distance = near; distance <= far; distance++) {
                    int x = edge + side * distance;
                    if (ink.core(x, rails.primary(x) - rail + dy, radius)) count++;
                }
                if (count >= far - near) return false;
            }
        }
        return true;
    }

    private static boolean coversBridges(Ink ink, Rails rail, List<Axis> axes, int radius, int margin,
            boolean allowFirstFused, boolean compressedOpening) {
        for (int i = 1; i < axes.size(); i++) {
            int start = axes.get(i - 1).x + margin, end = axes.get(i).x - margin;
            int both = 0, separated = 0;
            for (int x = start; x <= end; x++) {
                int primary = rail.primary(x);
                if (!ink.core(x, primary, radius)
                        || !ink.core(x, primary - rail.separation, radius)) continue;
                both++;
                if (!ink.core(x, primary - rail.separation / 2, radius)) separated++;
            }
            int samples = end - start + 1;
            if (both < .94f * samples) return false;
            if (compressedOpening) {
                // Continuous ink on both beam tracks must open into two separate rails before the final shaft.
                if (i < 3 ? separated > .20f * samples : separated < .45f * samples) return false;
            } else if ((!allowFirstFused || i > 1) && separated < .35f * samples) {
                return false;
            }
        }
        return true;
    }

    private static boolean fragmentInsideFusedRails(
            Ink ink, Rails rails, Fragment fragment, float gap, int radius) {
        int width = Math.round(.32f * gap);
        int x = Math.round(fragment.x);
        int dy = Math.round(.22f * gap);
        for (int col = x - width; col <= x + width; col++) {
            int lower = rails.primary(col);
            int upper = lower - rails.separation;
            int center = Math.round(fragment.y);
            if (center < upper - dy || center > lower + dy) return false;
            if (!ink.core(col, center, radius)
                    || !ink.core(col, lower - rails.separation / 2, radius)) return false;
        }
        return true;
    }

    private static boolean shortBackwardHook(
            Ink ink, Rails rails, int shaft, float gap, int radius) {
        int reach = Math.round(.74f * gap), join = Math.round(.23f * gap);
        int rear = Math.round(1.39f * gap), margin = Math.round(.86f * gap);
        int front = Math.round(.31f * gap);
        for (int depth = Math.round(.49f * gap); depth <= Math.round(.98f * gap); depth++) {
            int offset = rails.separation + depth;
            if (matches(ink, rails, shaft - reach, shaft - join, offset, radius, true)
                    && matches(ink, rails, shaft - join + 1, shaft, offset, radius, true)
                    && matches(ink, rails, shaft - rear, shaft - margin - 1, offset, radius, false)
                    && matches(ink, rails, shaft + front, shaft + reach, offset, radius, false))
                return true;
        }
        return false;
    }

    private static boolean matches(
            Ink ink, Rails rails, int left, int right, int offset, int radius, boolean wantedInk) {
        int hits = 0;
        for (int x = left; x <= right; x++)
            if (ink.core(x, rails.primary(x) - offset, radius) == wantedInk) hits++;
        return hits >= (right - left + 1) * 2f / 3;
    }

    static ScoreBeamSource projectDottedRibbons(ScoreBeamSource source, int page, List<Attack> attacks) {
        var origins = new ArrayList<>(source.noteOrigins());
        var counts = new HashMap<ScoreBeamSource.Anchor, Integer>();
        for (var origin : origins) origin.ifPresent(a -> counts.merge(a, 1, Integer::sum));
        var claims = new HashSet<ScoreBeamSource.Anchor>();
        var ids = new HashSet<String>();
        for (var group : source.groups()) {
            ids.add(group.id());
            for (var column : group.columns()) claims.addAll(column.members());
        }
        for (var column : source.detachedColumns()) claims.addAll(column.members());

        var groups = new ArrayList<>(source.groups());
        var hooks =
                List.of(
                        new ScoreBeamSource.Hook(1, 3, ScoreBeamSource.Direction.BACKWARD),
                        new ScoreBeamSource.Hook(3, 3, ScoreBeamSource.Direction.BACKWARD));
        for (int first = 0; first < attacks.size(); first += 4) {
            var columns = new ArrayList<ScoreBeamSource.Column>(4);
            for (var attack : attacks.subList(first, first + 4)) {
                var members = new ArrayList<ScoreBeamSource.Anchor>(2);
                for (int owner : new int[] {attack.upperOwner, attack.lowerOwner}) {
                    var held = origins.get(owner);
                    var anchor = held.orElseGet(() -> new ScoreBeamSource.Anchor(page, owner));
                    int expected = held.isPresent() ? 1 : 0;
                    if (anchor.pageIndex() != page
                            || counts.getOrDefault(anchor, 0) != expected
                            || !claims.add(anchor)) return source;
                    origins.set(owner, Optional.of(anchor));
                    counts.put(anchor, 1);
                    members.add(anchor);
                }
                columns.add(new ScoreBeamSource.Column(members));
            }
            String id = "printed-beam:" + page + ":" + columns.get(0).members().get(0).noteIndex();
            if (!ids.add(id)) return source;
            groups.add(new ScoreBeamSource.Group(id, columns, List.of(3, 3, 3), hooks));
        }
        return new ScoreBeamSource(groups, source.detachedColumns(), origins);
    }
}
