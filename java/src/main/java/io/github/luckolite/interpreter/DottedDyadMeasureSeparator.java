// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/** Recovers an interior rule separating two complete groups of dotted sixteenth-note dyads. */
final class DottedDyadMeasureSeparator {
    private DottedDyadMeasureSeparator() {}

    static List<MeasureRegion> recover(
            List<MeasureRegion> regions, byte[] labels, byte[] gray, int width, int height) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length) return regions;
        var result = new ArrayList<MeasureRegion>();
        for (int i = 0; i < regions.size(); i++) {
            var region = regions.get(i);
            var fragments = propose(regions, i, gray, width, height);
            if (!fragments.isEmpty()) {
                var written = OmrScoreInterpreter.analyze(labels, gray, width, height, fragments);
                if (qualifies(fragments, written.notes(), written.rests(), width, height)) {
                    result.addAll(fragments);
                    continue;
                }
            }
            result.add(region);
        }
        return result.size() == regions.size() ? regions : List.copyOf(result);
    }

    static List<MeasureRegion> propose(
            List<MeasureRegion> regions, int index, byte[] gray, int width, int height) {
        if (index < 2 || index + 1 >= regions.size()) return List.of();
        var region = regions.get(index);
        var reference = regions.get(index - 2);
        var preceding = regions.get(index - 1);
        var following = regions.get(index + 1);
        if (region.cadenceQuarterBeats() != 0
                || !sameSystem(reference, region) || !sameSystem(preceding, region)
                || !sameSystem(following, region)) return List.of();
        float left = region.left() * width, right = region.right() * width;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        float referenceSpan = (reference.right() - reference.left()) * width;
        float firstShortSpan = (preceding.right() - preceding.left()) * width;
        float secondShortSpan = (following.right() - following.left()) * width;
        if (gap < 6 || gap > 36 || referenceSpan < gap * 20 || referenceSpan > gap * 44
                || firstShortSpan < gap * 2 || firstShortSpan > gap * 6
                || secondShortSpan < gap * 2 || secondShortSpan > gap * 6
                || right - left < referenceSpan * 1.82f || right - left > referenceSpan * 2.18f)
            return List.of();
        float center = (left + right) * .5f;
        float hint = region.bottom() * height - gap * 2.1f;
        var rails = localRails(gray, width, height, Math.round(center), hint, gap);
        if (rails == null) return List.of();
        gap = rails[1];
        int top = Math.round(rails[0] - 4 * gap), bottom = Math.round(rails[0]);
        if (top < 0 || bottom >= height) return List.of();
        int cut = -1;
        for (int x = Math.round(center - gap * 2.6f); x <= Math.round(center + gap * 2.6f); x++) {
            if (!fullInk(gray, width, x, top, gap)) continue;
            int begin = x;
            while (x + 1 <= Math.round(center + gap * 2.6f) && fullInk(gray, width, x + 1, top, gap)) x++;
            if (x - begin + 1 < Math.max(3, Math.round(gap * .17f)) || x - begin + 1 > gap * .45f)
                continue;
            int axis = (begin + x) / 2;
            if (!OmrMeasurePostProcessor.verticalRuleAt(gray, width, axis, top, bottom, gap)
                    || ClosedHeadBarlineGuard.attached(gray, width, height, axis, top, bottom, gap)
                    || continuesBeyondStaff(gray, width, height, axis, top, bottom, gap)) continue;
            if (cut >= 0) return List.of();
            cut = axis;
        }
        if (cut < 0) return List.of();
        int padding = Math.max(2, Math.round(gap * .55f));
        return List.of(new MeasureRegion(region.left(), (cut - padding) / (float) width,
                            region.top(), region.bottom()),
                       new MeasureRegion((cut + padding) / (float) width, region.right(),
                            region.top(), region.bottom()));
    }

    private static boolean sameSystem(MeasureRegion a, MeasureRegion b) {
        float span = b.bottom() - b.top();
        return Math.abs(a.top() - b.top()) <= span * .25f
                && Math.abs(a.bottom() - b.bottom()) <= span * .25f;
    }

    static float[] localRails(
            byte[] gray, int width, int height, int x, float hint, float gap) {
        int inside = Math.round(gap * .3f), outside = Math.round(gap * .85f);
        int search = Math.max(2, Math.round(gap * .32f));
        if (x - outside < 0 || x + outside >= width || hint - 5 * gap < 0 || hint + gap >= height)
            return null;
        int[] found = new int[5];
        for (int line = 0; line < 5; line++) {
            int expected = Math.round(hint - (4 - line) * gap), best = 0;
            for (int y = expected - search; y <= expected + search; y++) {
                int left = 0, right = 0;
                for (int offset = inside; offset <= outside; offset++) {
                    if (thinHorizontalInk(gray, width, x - offset, y)) left++;
                    if (thinHorizontalInk(gray, width, x + offset, y)) right++;
                }
                int score = Math.min(left, right);
                if (score > best) {
                    found[line] = y;
                    best = score;
                }
            }
            if (best < (outside - inside + 1) * .6f) return null;
        }
        float measuredGap = (found[4] - found[0]) / 4f;
        for (int line = 1; line < 4; line++)
            if (Math.abs(found[line] - (found[0] + measuredGap * line)) > measuredGap * .16f)
                return null;
        return new float[]{found[4], measuredGap};
    }

    private static boolean thinHorizontalInk(byte[] gray, int width, int x, int y) {
        int tone = gray[y * width + x] & 255;
        return tone < 170 && (gray[(y - 4) * width + x] & 255) >= tone + 20
                && (gray[(y + 4) * width + x] & 255) >= tone + 20;
    }

    static boolean fullInk(byte[] gray, int width, int x, int top, float gap) {
        for (int space = 0; space < 4; space++) {
            int start = Math.round(top + (space + .22f) * gap);
            int end = Math.round(top + (space + .78f) * gap);
            int dark = 0;
            for (int y = start; y <= end; y++) if ((gray[y * width + x] & 255) < 155) dark++;
            if (dark < (end - start + 1) * .8f) return false;
        }
        return true;
    }

    static boolean continuesBeyondStaff(
            byte[] gray, int width, int height, int x, int top, int bottom, float gap) {
        int first = Math.round(gap * .32f), last = Math.round(gap * .95f);
        for (int side : new int[]{-1, 1}) {
            int edge = side < 0 ? top : bottom, dark = 0;
            for (int d = first; d <= last; d++) {
                int y = edge + side * d;
                if (y < 0 || y >= height) return true;
                if ((gray[y * width + x] & 255) < 155) dark++;
            }
            if (dark >= (last - first + 1) * .6f) return true;
        }
        return false;
    }

    static boolean qualifies(
            List<MeasureRegion> regions, List<ScoreNoteEvent> notes, List<ScoreRestEvent> rests,
            int width, int height) {
        if (regions.size() != 2 || !rests.isEmpty()) return false;
        int thirdBeamWitnesses = 0;
        for (int i = 0; i < regions.size(); i++) {
            int measure = i;
            var written = notes.stream().filter(n -> n.measureIndex() == measure)
                    .filter(DottedDyadMeasureSeparator::rhythmicHead)
                    .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure)).toList();
            var region = regions.get(i);
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            float span = (region.right() - region.left()) * width;
            if (written.size() < 20 || written.size() > 26) return false;
            int witnesses = alternatingDyads(written, gap / span);
            if (witnesses < 2) return false;
            thirdBeamWitnesses += witnesses;
        }
        return thirdBeamWitnesses >= 6;
    }

    /** Assigns the shorter duration only to heads whose own backward hook is visible. */
    static ScorePageInterpretation restorePrintedHooks(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        return restorePrintedHooks(score, gray, width, height, -1);
    }

    static ScorePageInterpretation restorePrintedHooks(
            ScorePageInterpretation score, byte[] gray, int width, int height, int physicalPage) {
        if (gray == null || gray.length != (long) width * height) return score;
        var bars = score.measures();
        var notes = score.notes();
        var corrections = Collections.newSetFromMap(new IdentityHashMap<ScoreNoteEvent, Boolean>());
        var beamSource = score.beamSource();
        var noteIndices = new IdentityHashMap<ScoreNoteEvent, Integer>();
        var meterChanges = score.meterChanges();
        ScoreMeterChange meter = null;
        int nextMeter = 0;
        for (int i = 2; i + 2 < bars.size(); i++) {
            while (nextMeter < meterChanges.size()
                    && meterChanges.get(nextMeter).measureIndex() <= i)
                meter = meterChanges.get(nextMeter++);
            if (meter == null
                    || meter.numerator() != 3
                    || meter.denominator() != 4
                    || nextMeter < meterChanges.size()
                            && meterChanges.get(nextMeter).measureIndex() == i + 1) continue;
            var left = bars.get(i);
            var right = bars.get(i + 1);
            if (left.cadenceQuarterBeats() != 0
                    || right.cadenceQuarterBeats() != 0
                    || !sameSystem(left, right)) continue;
            var merged = new MeasureRegion(left.left(), right.right(), left.top(), left.bottom());
            var neighbors = List.of(bars.get(i - 2), bars.get(i - 1), merged, bars.get(i + 2));
            if (!propose(neighbors, 2, gray, width, height).equals(List.of(left, right))) continue;
            int first = i;
            if (score.rests().stream()
                    .anyMatch(r -> r.measureIndex() == first || r.measureIndex() == first + 1))
                continue;
            List<List<PrintedHead>> openingRibbon = physicalPage >= 0 ? new ArrayList<>(5) : null;
            var leftHooks =
                    printedHookOwners(
                            left,
                            writtenHeads(notes, i),
                            gray,
                            width,
                            height,
                            false,
                            openingRibbon);
            if (leftHooks.isEmpty()) continue;
            List<List<PrintedHead>> closingRibbon = physicalPage >= 0 ? new ArrayList<>(5) : null;
            var rightHooks =
                    printedHookOwners(
                            right,
                            writtenHeads(notes, i + 1),
                            gray,
                            width,
                            height,
                            true,
                            closingRibbon);
            if (rightHooks.isEmpty()) continue;
            if (openingRibbon != null
                    && !openingRibbon.isEmpty()
                    && measureBeamEdge(left, gray, width, height, true)) {
                if (noteIndices.isEmpty()) {
                    for (int index = 0; index < notes.size(); index++)
                        noteIndices.put(notes.get(index), index);
                }
                beamSource =
                        captureOpeningRibbon(
                                beamSource,
                                openingRibbon,
                                noteIndices,
                                left,
                                gray,
                                width,
                                height,
                                physicalPage);
            }
            if (closingRibbon != null
                    && !closingRibbon.isEmpty()
                    && measureBeamEdge(right, gray, width, height, false)) {
                if (noteIndices.isEmpty()) {
                    for (int index = 0; index < notes.size(); index++)
                        noteIndices.put(notes.get(index), index);
                }
                var attacks = ribbonAttacks(closingRibbon, noteIndices);
                if (attacks.size() == 5)
                    beamSource =
                            ClosedOctaveBeamSource.captureClosingDottedDyads(
                                    beamSource,
                                    physicalPage,
                                    gray,
                                    width,
                                    height,
                                    (right.bottom() - right.top()) * height / 8.2f,
                                    attacks.get(0),
                                    attacks.subList(1, 5));
            }
            corrections.addAll(leftHooks);
            corrections.addAll(rightHooks);
        }
        corrections.removeIf(n -> n.beamCount() >= 3 || n.augmentationDots() != 0);
        var sourced = beamSource == score.beamSource() ? score : score.withBeamSource(beamSource);
        if (corrections.isEmpty()) return sourced;
        var revised = new ArrayList<ScoreNoteEvent>(notes.size());
        var origins = new ArrayList<Integer>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            revised.add(corrections.contains(n) ? withThirdBeam(n) : n);
            origins.add(i);
        }
        return sourced.withNotes(revised, origins);
    }

    private static List<ScoreNoteEvent> writtenHeads(List<ScoreNoteEvent> notes, int measure) {
        return notes.stream()
                .filter(n -> n.measureIndex() == measure && n.stemDirection() == -1)
                .filter(DottedDyadMeasureSeparator::rhythmicHead)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                .toList();
    }

    private static ScoreNoteEvent withThirdBeam(ScoreNoteEvent n) {
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                n.augmentationDots(),
                3,
                n.writtenAccidental(),
                n.unbeamedDurationBeats(),
                n.tupletDivisor(),
                n.followingRestBeats(),
                n.articulations(),
                n.clefBottomDiatonic(),
                n.crossStaffBeam(),
                n.leadingRestBeats(),
                n.compactOpening(),
                n.octaveShift(),
                n.boundaryTies(),
                n.tupletNormalNotes(),
                n.stemDirection(),
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }

    private record PrintedHead(ScoreNoteEvent note, float x, float y) {}

    private static List<ScoreNoteEvent> printedHookOwners(
            MeasureRegion bar,
            List<ScoreNoteEvent> notes,
            byte[] gray,
            int width,
            int height,
            boolean closingBar,
            List<List<PrintedHead>> beamRibbon) {
        if (notes.size() < 20 || notes.size() > 24) return List.of();
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var groups = new ArrayList<List<PrintedHead>>(12);
        for (var n : notes) {
            float x = left + n.positionInMeasure() * (right - left);
            if (groups.isEmpty() || x - groups.get(groups.size() - 1).get(0).x() > gap * .22f)
                groups.add(new ArrayList<>(2));
            var group = groups.get(groups.size() - 1);
            if (group.size() >= 2) return List.of();
            group.add(new PrintedHead(n, x, n.pageY() * height));
        }
        if (groups.size() != 12
                || groups.get(0).get(0).x() - left > 2.25f * gap
                || right - groups.get(11).get(0).x() > 2.25f * gap) return List.of();
        var owners = new ArrayList<ScoreNoteEvent>();
        int witnessHooks = 0, shaftDyads = 0;
        for (int i = 0; i < groups.size(); i++) {
            var heads = groups.get(i);
            float x = heads.get(0).x();
            if (i > 0) {
                float separation = x - groups.get(i - 1).get(0).x();
                if (separation < gap * 1.30f || separation > gap * 3.8f) return List.of();
            }
            if (heads.size() == 2 && sharesShaft(gray, width, height, heads, gap)) shaftDyads++;
            if (i % 2 != 0) continue;
            var shortAttack = groups.get(i + 1);
            float next = shortAttack.get(0).x();
            boolean dot =
                    heads.stream()
                            .anyMatch(
                                    h ->
                                            WrappedTripletDotInk.uniqueCore(
                                                            gray, width, height, h.x(), h.y(), next,
                                                            gap)
                                                    != null);
            if (!dot) return List.of();
            boolean hook = false;
            for (var shortHead : shortAttack) {
                boolean owned =
                        heads.stream()
                                .anyMatch(
                                        head ->
                                                OwnedTertiaryBeamlets
                                                        .provesBackwardHookOnJoinedRails(
                                                                gray,
                                                                width,
                                                                height,
                                                                shortHead.x(),
                                                                shortHead.y(),
                                                                head.x(),
                                                                head.y(),
                                                                gap));
                if (owned) {
                    hook = true;
                    owners.add(shortHead.note());
                }
            }
            if ((!hook || beamRibbon != null)
                    && closingBar
                    && i + 2 == groups.size()
                    && right - next <= gap * 1.25f
                    && terminalJoinedHook(gray, width, height, shortAttack, heads, gap)) {
                if (beamRibbon != null) beamRibbon.addAll(groups.subList(i - 3, i + 2));
                if (!hook && shortAttack.stream().allMatch(head -> head.note().beamCount() == 2)) {
                    for (var head : shortAttack) owners.add(head.note());
                    hook = true;
                }
            }
            if (!hook
                    && shortAttack.size() == 2
                    && heads.size() == 2
                    && hookBelowStaff(bar, heads, shortAttack, gray, width, height, gap)) {
                hook = true;
                for (var head : shortAttack) owners.add(head.note());
            }
            if (hook
                    && shortAttack.size() == 2
                    && sharesShaft(gray, width, height, shortAttack, gap)) {
                for (var head : shortAttack)
                    if (!owners.contains(head.note())) owners.add(head.note());
            }
            if (hook) witnessHooks++;
        }
        if (witnessHooks < 3 || shaftDyads < 8) return List.of();
        if (!closingBar && beamRibbon != null && groups.get(0).get(0).x() - left <= 1.5f * gap)
            beamRibbon.addAll(groups.subList(0, 5));
        return owners;
    }

    private static boolean hookBelowStaff(
            MeasureRegion bar,
            List<PrintedHead> peers,
            List<PrintedHead> owners,
            byte[] gray,
            int width,
            int height,
            float gap) {
        var a = owners.stream().sorted(Comparator.comparingDouble(PrintedHead::y)).toList();
        var b = peers.stream().sorted(Comparator.comparingDouble(PrintedHead::y)).toList();
        var staff = hookStaff(bar, gray, width, height, gap, a.get(0).x());
        return staff != null
                && OwnedTertiaryBeamlets.provesBackwardHookUnderStaffLine(
                        gray,
                        width,
                        height,
                        a.get(1).x(),
                        a.get(1).y(),
                        a.get(0).x(),
                        a.get(0).y(),
                        b.get(1).x(),
                        b.get(1).y(),
                        b.get(0).x(),
                        b.get(0).y(),
                        gap,
                        staff[0] - 4 * staff[1],
                        staff[1]);
    }

    private static float[] hookStaff(
            MeasureRegion bar, byte[] gray, int width, int height, float gap, float center) {
        float hint = bar.bottom() * height - gap * 2.1f;
        int stride = Math.max(2, Math.round(gap * .25f));
        int origin = Math.round(center);
        for (int offset = 0; offset <= gap * 6; offset += stride) {
            for (int x :
                    offset == 0
                            ? new int[] {origin}
                            : new int[] {origin + offset, origin - offset}) {
                if (x < bar.left() * width || x > bar.right() * width) continue;
                var staff = localRails(gray, width, height, x, hint, gap);
                if (staff != null) return staff;
            }
        }
        return null;
    }

    private static boolean measureBeamEdge(
            MeasureRegion bar, byte[] gray, int width, int height, boolean opening) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float edge = (opening ? bar.left() : bar.right()) * width;
        int center = Math.round(edge + gap * (opening ? -.55f : .55f));
        var staff =
                localRails(gray, width, height, center, bar.bottom() * height - gap * 2.1f, gap);
        if (staff == null) return false;
        gap = staff[1];
        int top = Math.round(staff[0] - 4 * gap), bottom = Math.round(staff[0]);
        if (top < 0 || bottom >= height) return false;
        int proved = 0;
        for (int x = Math.max(2, center - Math.round(gap * .22f));
                x <= Math.min(width - 3, center + Math.round(gap * .22f));
                x++) {
            if (fullInk(gray, width, x, top, gap)
                    && OmrMeasurePostProcessor.verticalRuleAt(gray, width, x, top, bottom, gap)
                    && !continuesBeyondStaff(gray, width, height, x, top, bottom, gap)) proved++;
        }
        return proved >= 2;
    }

    private static ScoreBeamSource captureOpeningRibbon(
            ScoreBeamSource source,
            List<List<PrintedHead>> ribbon,
            IdentityHashMap<ScoreNoteEvent, Integer> indices,
            MeasureRegion bar,
            byte[] gray,
            int width,
            int height,
            int page) {
        var attacks = ribbonAttacks(ribbon.subList(0, 4), indices);
        if (attacks.size() != 4) return source;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var staff = hookStaff(bar, gray, width, height, gap, attacks.get(3).upperX());
        if (staff == null) return source;
        return ClosedOctaveBeamSource.captureOpeningStaffCrossingDyads(
                source,
                page,
                gray,
                width,
                height,
                gap,
                staff[0] - 4 * staff[1],
                staff[1],
                attacks,
                ribbon.get(4).get(0).x());
    }

    private static List<ClosedOctaveBeamSource.Attack> ribbonAttacks(
            List<List<PrintedHead>> ribbon, IdentityHashMap<ScoreNoteEvent, Integer> indices) {
        var attacks = new ArrayList<ClosedOctaveBeamSource.Attack>(5);
        var vertically = Comparator.comparingDouble(PrintedHead::y);
        for (var heads : ribbon) {
            if (heads.size() != 2) return List.of();
            var upper = Collections.min(heads, vertically);
            var lower = Collections.max(heads, vertically);
            var u = indices.get(upper.note());
            var l = indices.get(lower.note());
            if (u == null || l == null) return List.of();
            attacks.add(
                    new ClosedOctaveBeamSource.Attack(
                            u, l, upper.x(), upper.y(), lower.x(), lower.y()));
        }
        return attacks;
    }

    private static boolean terminalJoinedHook(
            byte[] gray,
            int width,
            int height,
            List<PrintedHead> owners,
            List<PrintedHead> peers,
            float gap) {
        if (owners.size() != 2
                || peers.size() != 2
                || owners.stream()
                        .anyMatch(
                                head ->
                                        head.note().beamCount() < 2
                                                || head.note().beamCount() > 3
                                                || head.note().augmentationDots() != 0))
            return false;
        var vertically = Comparator.comparingDouble(PrintedHead::y);
        var upper = Collections.min(owners, vertically);
        var lower = Collections.max(owners, vertically);
        var peerUpper = Collections.min(peers, vertically);
        var peerLower = Collections.max(peers, vertically);
        return OwnedTertiaryBeamlets.provesJoinedClosingDyadHook(
                gray,
                width,
                height,
                lower.x(),
                lower.y(),
                upper.x(),
                upper.y(),
                peerUpper.x(),
                peerUpper.y(),
                peerLower.x(),
                peerLower.y(),
                gap,
                new PrintedBeamShafts.Budget(100_000));
    }

    private static boolean sharesShaft(
            byte[] gray, int width, int height, List<PrintedHead> heads, float gap) {
        var a = heads.get(0);
        var b = heads.get(1);
        float distance = Math.abs(a.y() - b.y());
        if (distance < gap * .75f || distance > gap * 3.3f) return false;
        var budget = new PrintedBeamShafts.Budget(45_000);
        var sa = PrintedBeamShafts.at(gray, width, height, a.x(), a.y(), gap, budget);
        var sb = PrintedBeamShafts.at(gray, width, height, b.x(), b.y(), gap, budget);
        return sa.stream()
                .filter(s -> s.direction() == 1)
                .anyMatch(
                        s ->
                                sb.stream()
                                        .anyMatch(
                                                t ->
                                                        t.direction() == 1
                                                                && Math.max(s.left(), t.left())
                                                                        <= Math.min(
                                                                                s.right(),
                                                                                t.right())
                                                                && Math.abs(s.endY() - t.endY())
                                                                        <= gap * .30f));
    }

    private static boolean rhythmicHead(ScoreNoteEvent note) {
        return note.kind() == ScoreNoteEvent.Kind.PITCHED && note.staffCount() == 1
                && note.staffIndex() == 0 && note.stemDirection() != 0 && note.beamCount() > 0
                && note.unbeamedDurationBeats() == 0 && note.tupletDivisor() == 1
                && note.augmentationDots() <= 1 && !note.crossStaffBeam()
                && (note.articulations() & NoteOrnament.GRACE) == 0;
    }

    private record Attack(float x, boolean dotted, boolean dyad, boolean shortWitness) {}

    private static int alternatingDyads(List<ScoreNoteEvent> notes, float gap) {
        var attacks = new ArrayList<Attack>();
        for (int i = 0; i < notes.size();) {
            var first = notes.get(i);
            int start = i++;
            while (i < notes.size()
                    && notes.get(i).positionInMeasure() - first.positionInMeasure() <= gap * .22f) i++;
            var group = notes.subList(start, i);
            boolean dotted = group.stream().anyMatch(n -> n.augmentationDots() == 1);
            boolean dyad = group.stream().filter(n -> (n.augmentationDots() > 0) == dotted)
                    .map(ScoreNoteEvent::staffStep).distinct().count() >= 2;
            boolean thirdBeam = group.stream().anyMatch(n -> n.beamCount() == 3);
            attacks.add(new Attack(first.positionInMeasure(), dotted, dyad, thirdBeam));
        }
        var dots = attacks.stream().filter(Attack::dotted).toList();
        if (dots.size() != 6 || attacks.get(0) != dots.get(0)
                || dots.get(0).x() > gap * 2.25f
                || attacks.get(attacks.size() - 1).x() < 1f - gap * 2.25f) return -1;
        int paired = (int) dots.stream().filter(Attack::dyad).count();
        int shortBeamWitnesses = 0, unmatched = 0;
        for (int i = 0; i < dots.size(); i++) {
            float start = dots.get(i).x(), end = i + 1 < dots.size() ? dots.get(i + 1).x() : 1;
            if (i + 1 < dots.size() && (end - start < gap * 4 || end - start > gap * 7)) return -1;
            var shorts = attacks.stream().filter(a -> !a.dotted() && a.x() > start && a.x() < end).toList();
            if (shorts.isEmpty() || shorts.size() > 2 || shorts.stream().filter(Attack::dyad).count() > 1)
                return -1;
            var shortAttack = shorts.get(shorts.size() - 1);
            if (shortAttack.x() - start < gap * 1.2f || shortAttack.x() - start > gap * 3.8f)
                return -1;
            if (shortAttack.dyad()) paired++;
            if (shortAttack.shortWitness()) shortBeamWitnesses++;
            unmatched += shorts.size() - 1;
        }
        return paired >= 9 && unmatched <= 1 ? shortBeamWitnesses : -1;
    }
}
