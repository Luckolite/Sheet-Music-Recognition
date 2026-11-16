// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Removes proved beam masks from a closed run of dotted and hooked thirds. */
final class ClosedDottedThirds {
    private ClosedDottedThirds() {}

    private record Head(int index, ScoreNoteEvent note, float x, float y) {}

    private record Dyad(Head upper, Head lower) {
        float x() {
            return (upper.x + lower.x) * .5f;
        }
    }

    private record Mask(int minX, int maxX, int minY, int maxY) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] labels,
            byte[] gray,
            int width,
            int height) {
        if (labels == null
                || gray == null
                || width < 256
                || height < 128
                || labels.length != (long) width * height
                || gray.length != labels.length) return score;
        var perMeasure = new ArrayList<List<Head>>();
        for (int i = 0; i < score.measures().size(); i++) perMeasure.add(new ArrayList<>());
        var notes = score.notes();
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            int index = note.measureIndex();
            if (index < 0 || index >= perMeasure.size()) continue;
            var bar = score.measures().get(index);
            float x = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
            perMeasure.get(index).add(new Head(i, note, x, note.pageY() * height));
        }
        boolean[] dropped = new boolean[notes.size()];
        boolean changed = false;
        var budget = new PrintedBeamShafts.Budget(2_000_000);
        var meter = inherited;
        int nextMeter = 0;
        for (int i = 0; i < perMeasure.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            final int index = i;
            var row = perMeasure.get(i);
            if (meter == null
                    || meter.numerator() != 3
                    || meter.denominator() != 4
                    || row.size() != 26) continue;
            var rests = score.rests().stream().filter(r -> r.measureIndex() == index).toList();
            if (rests.size() > 1) continue;
            var masks =
                    closedRun(
                            row,
                            score.measures().get(i),
                            rests.isEmpty() ? null : rests.get(0),
                            labels,
                            gray,
                            width,
                            height,
                            budget);
            if (masks.isEmpty()) continue;
            for (var mask : masks) dropped[mask.index] = true;
            changed = true;
        }
        if (!changed) return score;
        var kept = new ArrayList<ScoreNoteEvent>();
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (dropped[i]) continue;
            kept.add(notes.get(i));
            origins.add(i);
        }
        return score.withNotes(kept, origins);
    }

    static ScorePageInterpretation removeDenseInsertedMarks(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, byte[] original,
            int width, int height, int physicalPage) {
        if (gray == null || original == null || width < 256 || height < 128
                || gray.length != (long) width * height || gray.length != original.length) return score;
        var rows = new ArrayList<List<Head>>();
        for (int i = 0; i < score.measures().size(); i++) rows.add(new ArrayList<>());
        var notes = score.notes();
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.measureIndex() < 0 || n.measureIndex() >= rows.size()) continue;
            var bar = score.measures().get(n.measureIndex());
            float x = (bar.left() + n.positionInMeasure() * (bar.right() - bar.left())) * width;
            rows.get(n.measureIndex()).add(new Head(i, n, x, n.pageY() * height));
        }
        var meter = inherited;
        int nextMeter = 0;
        var removed = new java.util.BitSet(notes.size());
        var beams = score.beamSource();
        for (int index = 0; index < rows.size(); index++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= index)
                meter = score.meterChanges().get(nextMeter++);
            var row = rows.get(index);
            final int m = index;
            if (row.size() != 33 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == m)) continue;
            var bar = score.measures().get(index);
            if (bar.cadenceQuarterBeats() != 0) continue;
            var mark = denseInterstitialMark(row, bar, gray, original, width, height);
            if (mark == null) continue;
            removed.set(mark.index);
            var owners = row.stream().filter(h -> h != mark).sorted(Comparator.comparingDouble(Head::x))
                    .map(Head::index).toList();
            beams = ClosedDottedSixteenths.captureRestoredDyadBeams(
                    beams, notes, owners, bar, gray, original, width, height, physicalPage);
        }
        if (removed.isEmpty()) return score;
        var kept = new ArrayList<ScoreNoteEvent>();
        var sourceIndices = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (removed.get(i)) continue;
            kept.add(notes.get(i));
            sourceIndices.add(i);
        }
        return score.withBeamSource(beams).withNotes(kept, sourceIndices);
    }

    private static Head denseInterstitialMark(List<Head> row, MeasureRegion bar,
            byte[] gray, byte[] original, int width, int height) {
        var stemless = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (stemless.size() != 1 || row.stream().anyMatch(h -> !ordinary(h.note))) return null;
        var mark = stemless.get(0);
        if (mark.note.beamCount() != 2 || mark.note.augmentationDots() != 0
                || mark.note.articulations() != 0
                || mark.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) return null;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 27 || !fillsPastHead(gray, width, height, mark.x, mark.y, gap)
                || !fillsPastHead(original, width, height, mark.x, mark.y, gap)) return null;
        var owners = row.stream().filter(h -> h != mark).sorted(Comparator.comparingDouble(Head::x)).toList();
        var attacks = new ArrayList<Dyad>(16);
        for (int i = 0; i < owners.size(); i += 2) {
            var left = owners.get(i);
            var right = owners.get(i + 1);
            var upper = left.y < right.y ? left : right;
            var lower = upper == left ? right : left;
            if (right.x - left.x > .21f * gap || lower.y - upper.y < .83f * gap
                    || lower.y - upper.y > 1.24f * gap
                    || upper.note.staffStep() - lower.note.staffStep() != 2
                    || upper.note.stemDirection() != lower.note.stemDirection()
                    || upper.note.clefBottomDiatonic() != owners.get(0).note.clefBottomDiatonic()
                    || lower.note.clefBottomDiatonic() != owners.get(0).note.clefBottomDiatonic()
                    || upper.note.beamCount() != lower.note.beamCount()
                    || upper.note.augmentationDots() != (i % 4 == 0 ? 1 : 0)
                    || lower.note.augmentationDots() != (i % 4 == 0 ? 1 : 0)) return null;
            attacks.add(new Dyad(upper, lower));
        }
        float left = bar.left() * width, right = bar.right() * width;
        if (attacks.get(0).x() - left < gap || right - attacks.get(15).x() > gap * 1.5f
                || !ClosedDottedSixteenths.hasClosingRule(gray, width, height, bar)
                || !ClosedDottedSixteenths.hasClosingRule(original, width, height, bar)) return null;
        var notes = owners.stream().map(Head::note).toList();
        boolean paired = false;
        for (int i = 0; i < attacks.size(); i++) {
            var dyad = attacks.get(i);
            float following = i + 1 < attacks.size() ? attacks.get(i + 1).x() : right;
            boolean dotted = i % 2 == 0;
            if (i + 1 < attacks.size() && (following - dyad.x() < 1.28f * gap
                    || following - dyad.x() > 3.4f * gap)) return null;
            if ((dot(gray, width, height, dyad.upper, following, gap)
                    || dot(gray, width, height, dyad.lower, following, gap)) != dotted
                    || (dot(original, width, height, dyad.upper, following, gap)
                            || dot(original, width, height, dyad.lower, following, gap)) != dotted) return null;
            if (dotted && mark.x - dyad.x() >= gap && following - mark.x >= gap) {
                var shortAttack = attacks.get(i + 1);
                if (foundTwoPrintedStems(gray, width, height, mark, dyad, shortAttack, gap)
                        && foundTwoPrintedStems(original, width, height, mark, dyad, shortAttack, gap))
                    paired = true;
            }
            for (var member : List.of(dyad.upper.note, dyad.lower.note)) {
                var place = ScoreNoteTiming.writtenPlacement(member, notes, 4f).orElse(null);
                if (place == null || place.onsetBeats() != i / 2 * .5 + (dotted ? 0 : .375)
                        || place.durationBeats() != (dotted ? .375 : .125)) return null;
            }
        }
        return paired ? mark : null;
    }

    /** A tall solid mark covers the would-be oval well above and below its center. */
    static boolean fillsPastHead(byte[] gray, int width, int height, float x, float y, float gap) {
        if (gray == null || width < 1 || height < 1 || gray.length != (long) width * height
                || !Float.isFinite(x + y + gap) || gap < 10 || gap > 27) return false;
        int center = Math.round(x), row = Math.round(y), span = Math.round(gap * .42f);
        int top = Math.round(row - gap * 1.25f), bottom = Math.round(row + gap * .54f);
        if (center - span < 0 || center + span >= width || top < 0 || bottom >= height) return false;
        int fullRows = 0;
        for (int yy = top; yy <= bottom; yy++) {
            int dark = 0;
            for (int xx = center - span; xx <= center + span; xx++)
                if ((gray[yy * width + xx] & 255) < 150) dark++;
            if (dark >= (2 * span + 1) * .88f) fullRows++;
        }
        return fullRows >= (bottom - top + 1) * .88f;
    }

    private static boolean foundTwoPrintedStems(byte[] gray, int width, int height,
            Head mark, Dyad before, Dyad after, float gap) {
        var budget = new PrintedBeamShafts.Budget(80_000);
        return mark.y - after.lower.y >= gap * .28f && mark.y - after.lower.y <= gap * .88f
                && PrintedStemDirection.detect(gray, width, height, mark.x, mark.y, gap) == 0
                && PrintedBeamShafts.at(gray, width, height, mark.x, mark.y, gap, budget).isEmpty()
                && sharedStemOnSide(gray, width, height, before, gap, budget)
                && sharedStemOnSide(gray, width, height, after, gap, budget)
                && !budget.refused();
    }

    private static boolean sharedStemOnSide(byte[] gray, int width, int height, Dyad pair,
            float gap, PrintedBeamShafts.Budget budget) {
        var top = PrintedBeamShafts.at(gray, width, height, pair.upper.x, pair.upper.y, gap, budget);
        var bottom = PrintedBeamShafts.at(gray, width, height, pair.lower.x, pair.lower.y, gap, budget);
        if (top.size() != 1 || bottom.size() != 1) return false;
        var a = top.get(0);
        var b = bottom.get(0);
        return a.direction() == -pair.upper.note.stemDirection() && a.direction() == b.direction()
                && Math.max(a.left(), b.left()) <= Math.min(a.right(), b.right())
                && Math.abs(a.endY() - b.endY()) <= 1;
    }

    private static List<Head> closedRun(
            List<Head> row,
            MeasureRegion bar,
            ScoreRestEvent rest,
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            PrintedBeamShafts.Budget budget) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !ordinary(h.note)))
            return List.of();
        var reverse = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
        var stemless = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (reverse.size() != 1 || stemless.size() != 1) return List.of();
        var incoming = reverse.get(0);
        var outgoing = stemless.get(0);
        if (incoming.note.beamCount() != 2
                || outgoing.note.beamCount() != 3
                || incoming.note.augmentationDots() != 0
                || outgoing.note.augmentationDots() != 0
                || incoming.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || outgoing.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
            return List.of();
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 27 || right - left < gap * 45 || right - left > gap * 55)
            return List.of();
        var incomingMask = beamMask(labels, gray, width, height, incoming, gap);
        var outgoingMask = beamMask(labels, gray, width, height, outgoing, gap);
        if (incomingMask == null || outgoingMask == null) return List.of();
        var owners =
                row.stream()
                        .filter(h -> h.note.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (owners.size() != 24) return List.of();
        var dyads = new ArrayList<Dyad>();
        for (int i = 0; i < owners.size(); i += 2) {
            var a = owners.get(i);
            var b = owners.get(i + 1);
            var top = a.y < b.y ? a : b;
            var low = top == a ? b : a;
            if (b.x - a.x > gap * .20f
                    || low.y - top.y < gap * .80f
                    || low.y - top.y > gap * 1.27f
                    || top.note.staffStep() - low.note.staffStep() != 2
                    || top.note.clefBottomDiatonic() != low.note.clefBottomDiatonic()
                    || top.note.beamCount() != low.note.beamCount()
                    || top.note.augmentationDots() != low.note.augmentationDots()
                    || top.note.clefBottomDiatonic() != owners.get(0).note.clefBottomDiatonic())
                return List.of();
            dyads.add(new Dyad(top, low));
        }
        if (dyads.get(0).x() - left < gap
                || dyads.get(0).x() - left > gap * 2.75f
                || right - dyads.get(11).x() < gap
                || right - dyads.get(11).x() > gap * 2.75f
                || !ClosedDottedSixteenths.hasClosingRule(gray, width, height, bar))
            return List.of();

        boolean reverseOwned = false, stemlessOwned = false;
        for (int i = 0; i < dyads.size(); i++) {
            var group = dyads.get(i);
            float next = i + 1 < dyads.size() ? dyads.get(i + 1).x() : right;
            boolean dotted = i % 2 == 0;
            if (i + 1 < dyads.size()
                            && (next - group.x() < gap * 2.9f || next - group.x() > gap * 5.0f)
                    || group.lower.note.augmentationDots() != (dotted ? 1 : 0)
                    || dotted && group.lower.note.beamCount() > 2
                    || !dotted && group.lower.note.beamCount() < 2
                    || (dot(gray, width, height, group.upper, next, gap)
                                    || dot(gray, width, height, group.lower, next, gap))
                            != dotted) return List.of();
            if (!dotted) continue;
            var hook = dyads.get(i + 1);
            if (incoming.x > group.x()
                    && incoming.x < hook.x()
                    && borrowsFollowingStem(
                            gray,
                            width,
                            height,
                            incoming,
                            incomingMask,
                            hook,
                            rest,
                            bar,
                            gap,
                            budget)
                    && provedHook(gray, width, height, group, hook, gap)) reverseOwned = true;
            if (Math.abs(outgoing.x - group.x()) < gap * .5f
                    && occupiesStartingRail(
                            gray, width, height, outgoing, outgoingMask, group, gap, budget)
                    && provedHook(gray, width, height, group, hook, gap)) stemlessOwned = true;
        }
        if (!reverseOwned || !stemlessOwned || budget.refused()) return List.of();
        var raw = owners.stream().map(Head::note).toList();
        for (int attack = 0; attack < dyads.size(); attack++) {
            float start = attack / 2 * .5f + (attack % 2 == 0 ? 0 : .375f);
            float length = attack % 2 == 0 ? .375f : .125f;
            for (var note : List.of(dyads.get(attack).upper.note, dyads.get(attack).lower.note)) {
                var time = ScoreNoteTiming.writtenPlacement(note, raw, 3f).orElse(null);
                if (time == null || time.onsetBeats() != start || time.durationBeats() != length)
                    return List.of();
            }
        }
        return List.of(incoming, outgoing);
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.unbeamedDurationBeats() == 0
                && n.beamCount() >= 1
                && n.beamCount() <= 3
                && n.tupletDivisor() == 1
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && !n.tiedFromPrevious()
                && n.boundaryTies() == 0
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && (n.articulations() & NoteOrnament.GRACE) == 0
                && !n.crossStaffBeam()
                && n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN;
    }

    private static boolean dot(byte[] gray, int width, int height, Head h, float next, float gap) {
        return WrappedTripletDotInk.uniqueCore(gray, width, height, h.x, h.y, next, gap) != null
                || LegacyTripletDotInk.singleDotCore(gray, width, height, h.x, h.y, next, gap);
    }

    private static boolean provedHook(
            byte[] gray, int width, int height, Dyad dotted, Dyad hooked, float gap) {
        return OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                gray,
                width,
                height,
                hooked.lower.x,
                hooked.lower.y,
                dotted.lower.x,
                dotted.lower.y,
                gap);
    }

    private static boolean borrowsFollowingStem(
            byte[] gray,
            int width,
            int height,
            Head incoming,
            Mask mask,
            Dyad owner,
            ScoreRestEvent rest,
            MeasureRegion bar,
            float gap,
            PrintedBeamShafts.Budget budget) {
        var reversed =
                PrintedBeamShafts.at(gray, width, height, incoming.x, incoming.y, gap, budget);
        var down =
                PrintedBeamShafts.at(
                        gray, width, height, owner.lower.x, owner.lower.y, gap, budget);
        if (reversed.size() != 1 || down.size() != 1) return false;
        var a = reversed.get(0);
        var b = down.get(0);
        return a.direction() == -1
                && b.direction() == 1
                && Math.max(a.left(), b.left()) <= Math.min(a.right(), b.right())
                && Math.abs(a.endY() - owner.upper.y) < gap * .32f
                && a.startY() >= mask.minY
                && a.startY() <= mask.maxY
                && b.left() > mask.minX + gap
                && b.right() < mask.maxX
                && b.endY() - incoming.y > gap * .35f
                && b.endY() - incoming.y < gap
                && (rest == null || restsOnHook(gray, width, height, rest, bar, incoming, b, gap));
    }

    /** An exported half-rest candidate may be the same printed hook above the borrowed mask. */
    private static boolean restsOnHook(
            byte[] gray,
            int width,
            int height,
            ScoreRestEvent rest,
            MeasureRegion bar,
            Head incoming,
            PrintedBeamShafts.Shaft shaft,
            float gap) {
        if (rest.kind() != ScoreRestEvent.Kind.LITERAL
                || rest.durationBeats() != 2
                || rest.staffIndex() != 0
                || rest.staffCount() != 1
                || rest.pageHeight() * height < gap * .15f
                || rest.pageHeight() * height > gap * .6f) return false;
        float x = (bar.left() + rest.positionInMeasure() * (bar.right() - bar.left())) * width;
        float y = rest.pageY() * height;
        int from = Math.round(x), line = Math.round(y), axis = shaft.left();
        if (Math.abs(x - incoming.x) > gap * .32f
                || axis - x < gap * .36f
                || axis - x > gap * .82f
                || shaft.endY() - y < gap * 1.65f
                || shaft.endY() - y > gap * 2.25f
                || line < 1
                || line >= height - 1
                || from < 1
                || axis >= width) return false;
        for (int col = from; col <= axis; col++) {
            for (int row = line - 1; row <= line + 1; row++) {
                if ((gray[row * width + col] & 255) >= 155) return false;
            }
        }
        return true;
    }

    private static boolean occupiesStartingRail(
            byte[] gray,
            int width,
            int height,
            Head outgoing,
            Mask mask,
            Dyad owner,
            float gap,
            PrintedBeamShafts.Budget budget) {
        if (!PrintedBeamShafts.at(gray, width, height, outgoing.x, outgoing.y, gap, budget)
                .isEmpty()) return false;
        var down =
                PrintedBeamShafts.at(
                        gray, width, height, owner.lower.x, owner.lower.y, gap, budget);
        if (down.size() != 1) return false;
        var stem = down.get(0);
        return stem.direction() == 1
                && Math.abs(mask.minX - stem.left()) < gap * .24f
                && mask.maxX - stem.right() > gap * 1.65f
                && stem.endY() >= mask.maxY - gap * .2f
                && stem.endY() <= mask.maxY + gap * .80f
                && outgoing.y - owner.lower.y > gap * 3.3f;
    }

    private static Mask beamMask(
            byte[] labels, byte[] gray, int width, int height, Head head, float gap) {
        int x = Math.round(head.x), y = Math.round(head.y), radius = Math.round(gap * 2);
        if (x < radius
                || y < radius
                || x + radius >= width
                || y + radius >= height
                || labels[y * width + x] != OmrMeasurePostProcessor.NOTEHEAD) return null;
        int size = radius * 2 + 1;
        int startX = x - radius, startY = y - radius;
        int[] pending = new int[size * size];
        boolean[] visited = new boolean[pending.length];
        int center = radius * size + radius;
        pending[0] = center;
        visited[center] = true;
        int count = 1, minX = x, maxX = x, minY = y, maxY = y, ink = 0;
        long sumX = 0, sumY = 0;
        var neighbors = new int[] {-1, 1, -size, size};
        for (int i = 0; i < count; i++) {
            int local = pending[i], col = local % size, row = local / size;
            if (col == 0 || row == 0 || col == size - 1 || row == size - 1) return null;
            int pageX = startX + col, pageY = startY + row;
            sumX += pageX;
            sumY += pageY;
            minX = Math.min(minX, pageX);
            maxX = Math.max(maxX, pageX);
            minY = Math.min(minY, pageY);
            maxY = Math.max(maxY, pageY);
            if ((gray[pageY * width + pageX] & 255) < 155) ink++;
            for (int offset : neighbors) {
                int n = local + offset;
                int nX = startX + n % size, nY = startY + n / size;
                if (!visited[n] && labels[nY * width + nX] == OmrMeasurePostProcessor.NOTEHEAD) {
                    visited[n] = true;
                    pending[count++] = n;
                }
            }
        }
        int span = maxX - minX + 1, thickness = maxY - minY + 1;
        if (span < gap * 1.98f
                || span > gap * 2.65f
                || thickness < gap * .53f
                || thickness > gap * 1.22f
                || span < thickness * 2.0f
                || ink < count * .97f
                || count < span * thickness * .52f
                || Math.abs(sumX / (float) count - head.x) > .25f
                || Math.abs(sumY / (float) count - head.y) > .25f) return null;
        return new Mask(minX, maxX, minY, maxY);
    }
}
