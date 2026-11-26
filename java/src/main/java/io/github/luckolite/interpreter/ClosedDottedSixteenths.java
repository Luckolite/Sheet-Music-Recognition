// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.IntStream;
import java.util.List;

/** Reads physically closed runs of dotted sixteenths and their hooks. */
final class ClosedDottedSixteenths {
    private ClosedDottedSixteenths() {}

    private record Head(int index,ScoreNoteEvent note,float x,float y) {}
    private record Shaft(int x,int tip) {}
    private record Beam(Shaft a,Shaft b,int depth,int separation) {
        float outer(float x) {return a.tip+(b.tip-a.tip)*(x-a.x)/(b.x-a.x)-depth;}
    }
    private record Ink(byte[] pixels,int w,int h) {
        boolean dark(int x,int y) {
            return x>=0 && x<w && y>=0 && y<h && (pixels[y*w+x]&255)<130;
        }

        int weight(int x,int y,int radius) {
            int n=0;
            for(int k=-radius;k<=radius;k++) if(dark(x,y+k)) n++;
            return n;
        }
    }

    static ScorePageInterpretation apply(ScorePageInterpretation score,byte[] gray,int w,int h) {
        if (gray==null || gray.length!=(long)w*h || w<256 || h<128) return score;
        var ink=new Ink(gray,w,h);
        var notes=score.notes();
        var bars = groupedHeads(score, w, h);
        var corrected=new ScoreNoteEvent[notes.size()];
        var suppressed=new boolean[notes.size()];
        int nextMeter=0;
        ScoreMeterChange meter=null;
        boolean changed=false;
        for(int m=0;m<bars.size();m++) {
            while (nextMeter<score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex()<=m)
                meter=score.meterChanges().get(nextMeter++);
            final int bar=m;
            var row=bars.get(m);
            if (meter == null
                    || meter.denominator() != 4
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)) continue;
            if (meter.numerator() == 4 && row.size() == 34) {
                var aliases = joinedDyadRailAliases(ink, score.measures().get(m), row);
                for (var alias : aliases) suppressed[alias.index] = true;
                if (!aliases.isEmpty()) changed = true;
                continue;
            }
            if (meter.numerator() == 4 && row.size() == 14) {
                if (closedEighthAndDottedPairs(ink, score.measures().get(m), row, corrected))
                    changed = true;
                continue;
            }
            if (meter.numerator() == 4 && row.size() == 17) {
                var run = closedCommonTimeRun(ink, score.measures().get(m), row);
                if (run != null) {
                    suppressed[run.fragment.index] = true;
                    for (int i = 0; i < run.kept.size(); i++) {
                        var head = run.kept.get(i);
                        corrected[head.index] = rhythm(head.note, i % 2 == 0);
                    }
                    changed = true;
                }
                continue;
            }
            if (meter.numerator() != 3 || row.size() != 13) continue;
            var proof=prove(ink,score.measures().get(m),row);
            if (proof == null && meter.measureIndex() == m && m + 1 < bars.size())
                proof =
                        inlineMeterHooks(
                                ink, score.measures().get(m), score.measures().get(m + 1), row);
            if (proof == null) {
                var hook = backwardHookAlias(ink, score.measures().get(m), row);
                if (hook == null) continue;
                suppressed[hook.index] = true;
                changed = true;
                continue;
            }
            suppressed[proof.fragment.index]=true;
            for(int i=0;i<proof.kept.size();i++)
                corrected[proof.kept.get(i).index]=rhythm(proof.kept.get(i).note,i%2==0);
            changed=true;
        }
        if (!changed) return score;
        var retained=new ArrayList<ScoreNoteEvent>();
        var owners=new ArrayList<Integer>();
        for(int i=0;i<notes.size();i++) {
            if (suppressed[i]) continue;
            owners.add(i);
            retained.add(corrected[i]==null?notes.get(i):corrected[i]);
        }
        return score.withNotes(retained,owners);
    }

    static ScorePageInterpretation repairClosedThirdsClock(
            ScorePageInterpretation score, byte[] gray, byte[] source, int w, int h) {
        if (gray == null || source == null || gray.length != (long) w * h
                || source.length != gray.length || w < 256 || h < 128) return score;
        var rows = groupedHeads(score, w, h);
        var raw = new Ink(source, w, h);
        var normalized = new Ink(gray, w, h);
        var notes = new ArrayList<>(score.notes());
        var removed = new java.util.BitSet(notes.size());
        int nextMeter = 0;
        ScoreMeterChange meter = null;
        for (int i = 0; i < rows.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            if (meter == null || meter.denominator() != 4) continue;
            var row = rows.get(i);
            if (meter.numerator() == 3 && row.size() == 13
                    && score.rests().stream().noneMatch(r -> r.measureIndex() == row.get(0).note.measureIndex())) {
                var proof = closedUpperRibbon(raw, normalized, score.measures().get(i), row);
                if (proof != null) {
                    removed.set(proof.fragment.index);
                    for (int slot = 0; slot < proof.kept.size(); slot++) {
                        var head = proof.kept.get(slot);
                        notes.set(head.index, rhythm(head.note, slot % 2 == 0));
                    }
                    continue;
                }
            }
            boolean openingTriple = meter.numerator() == 3 && row.size() == 25 && i > 0
                    && score.measures().get(i).top() > score.measures().get(i - 1).bottom();
            boolean common = meter.numerator() == 4 && (row.size() == 18 || row.size() == 27 || row.size() == 31 || row.size() == 36);
            boolean thirdsTriple = meter.numerator() == 3 && row.size() == 24;
            if (!openingTriple && !common && !thirdsTriple) continue;
            int measure = i;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == measure)
                    || score.multiRestGroups().stream().anyMatch(g -> g.firstMeasureIndex() <= measure
                            && g.firstMeasureIndex() + g.measureCount() > measure)) continue;
            var bar = score.measures().get(i);
            if (thirdsTriple) {
                for (var head : closedOpposedThirds(raw, normalized, bar, row))
                    notes.set(head.index, head.note);
                continue;
            }
            if (openingTriple) {
                var ribbon = closedDownstemOpeningTriple(raw, normalized, bar, row);
                if (ribbon == null) continue;
                removed.set(ribbon.alias.index);
                for (int j = 0; j < ribbon.heads.size(); j++) {
                    var head = ribbon.heads.get(j);
                    notes.set(head.index, rhythm(head.note, j % 4 < 2));
                }
                continue;
            }
            if (row.size() == 18) {
                var hooks = closedWideDyadsBeforeHalf(raw, normalized, bar, row);
                for (var pair : hooks) {
                    notes.set(pair.upper.index, rhythm(pair.upper.note, false));
                    notes.set(pair.lower.index, rhythm(pair.lower.note, false));
                }
                continue;
            }
            var proof = switch (row.size()) {
                case 27 -> closedThirdsWithQuarterTail(raw, normalized, bar, row);
                case 31 -> closedThirdsClock(raw, normalized, bar, row);
                default -> closedOctavesClock(raw, normalized, bar, row);
            };
            if (proof == null) continue;
            for (var mask : proof.masks) removed.set(mask.index);
            for (var pair : proof.shortPairs) {
                notes.set(pair.upper.index, rhythm(pair.upper.note, false));
                notes.set(pair.lower.index, rhythm(pair.lower.note, false));
            }
        }
        if (removed.isEmpty() && notes.equals(score.notes())) return score;
        var keep = new ArrayList<ScoreNoteEvent>();
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < notes.size(); i++) {
            if (removed.get(i)) continue;
            keep.add(notes.get(i));
            origins.add(i);
        }
        return score.withNotes(keep, origins);
    }

    private record ThirdsClock(List<Head> masks, List<Dyad> shortPairs) {}

    private static List<Head> closedOpposedThirds(Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23
                || !hasClosingRule(raw.pixels, raw.w, raw.h, bar)
                || !hasClosingRule(normalized.pixels, raw.w, raw.h, bar)) return List.of();
        var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var firstDirection = ordered.get(0).note.stemDirection();
        if (firstDirection == 0 || ordered.get(16).note.stemDirection() != -firstDirection) return List.of();
        var trial = new ArrayList<ScoreNoteEvent>(24);
        var replaced = new ArrayList<Head>();
        int removedDots = 0;
        boolean restoredHook = false;
        for (int i = 0; i < ordered.size(); i += 2) {
            var pair = thirdsDyad(ordered.subList(i, i + 2));
            boolean dotted = i % 4 == 0;
            int direction = i < 16 ? firstDirection : -firstDirection;
            if (Math.abs(pair.lower.x - pair.upper.x) > gap * .22f
                    || pair.upper.note.staffStep() - pair.lower.note.staffStep() != 2
                    || i > 0 && (pair.lower.x - ordered.get(i - 2).x < gap * 1.35f
                            || pair.lower.x - ordered.get(i - 2).x > gap * 4.4f)) return List.of();
            boolean missedHook = !dotted && pair.lower.note.beamCount() == 2;
            boolean missedDot = dotted && pair.upper.note.augmentationDots() + pair.lower.note.augmentationDots() == 0;
            if (missedHook) {
                if (i != 2 || direction != 1 || restoredHook) return List.of();
                var prior=thirdsDyad(ordered.subList(0, 2));
                if (!coveredUpwardHook(raw, pair, prior, gap) || !coveredUpwardHook(normalized, pair, prior, gap))
                    return List.of();
                restoredHook = true;
            }
            float next = i + 2 < ordered.size() ? ordered.get(i + 2).x : bar.right() * raw.w;
            if (missedDot) {
                if (i != 0 && i != 20 || ++removedDots > 2) return List.of();
                for (var plane : List.of(raw, normalized)) {
                    if (!dot(plane, pair.upper, next, gap) && !compactDottedHead(plane, pair.upper, gap)
                            && !dot(plane, pair.lower, next, gap) && !compactDottedHead(plane, pair.lower, gap))
                        return List.of();
                }
            }
            for (var owner : ordered.subList(i, i + 2)) {
                var n = owner.note;
                if (!regular(n, 1, true) || n.stemDirection() != 0 && n.stemDirection() != direction
                        || n.beamCount() != (dotted || missedHook ? 2 : 3)
                        || !dotted && n.augmentationDots() != 0) return List.of();
                var result = missedDot || missedHook ? rhythm(n, dotted) : n;
                trial.add(result);
                if (result != n) replaced.add(new Head(owner.index, result, owner.x, owner.y));
            }
        }
        if (removedDots == 0 || replaced.isEmpty()) return List.of();
        for (int i = 0; i < trial.size(); i++) {
            int slot = i / 2;
            boolean dotted = slot % 2 == 0;
            var place = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 3).orElse(null);
            if (place == null || place.onsetBeats() != slot / 2 * .5 + (dotted ? 0 : .375)
                    || place.durationBeats() != (dotted ? .375 : .125)) return List.of();
        }
        return replaced;
    }

    private static boolean compactDottedHead(Ink ink, Head head, float gap) {
        int r = Math.round(gap * .125f), near = Math.round(gap * .20f), far = Math.round(gap * .30f);
        int left = Math.round(head.x + gap * .68f), right = Math.round(head.x + gap * 1.2f);
        int top = Math.round(head.y - gap * .67f), bottom = Math.round(head.y + gap * .3f);
        if (left < far || right + far >= ink.w || top < far || bottom + far >= ink.h) return false;
        int side = 2 * r + 1;
        for (int x = left; x <= right; x++) {
            for (int y = top; y <= bottom; y++) {
                int core = 0;
                for (int dx = -r; dx <= r; dx++)
                    for (int dy = -r; dy <= r; dy++)
                        if ((ink.pixels[(y + dy) * ink.w + x + dx] & 255) < 120) core++;
                if (core < .75f * side * side) continue;
                if (clearedDotFaces(ink, x, y, r, near, far, -1)
                        && clearedDotFaces(ink, x, y, r, near, far, 1)) return true;
            }
        }
        return false;
    }

    private static boolean clearedDotFaces(Ink ink, int x, int y, int radius, int near, int far, int direction) {
        boolean separatedRow = false, separatedColumn = false;
        for (int offset = near; offset <= far; offset++) {
            int row = 0, column = 0;
            for (int k = -radius; k <= radius; k++) {
                if ((ink.pixels[(y + direction * offset) * ink.w + x + k] & 255) < 125) row++;
                if ((ink.pixels[(y + k) * ink.w + x + direction * offset] & 255) < 125) column++;
            }
            separatedRow |= row <= 1;
            separatedColumn |= column <= 1;
        }
        return separatedRow && separatedColumn;
    }

    private static List<Dyad> closedWideDyadsBeforeHalf(Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23
                || !hasClosingRule(raw.pixels, raw.w, raw.h, bar)
                || !hasClosingRule(normalized.pixels, raw.w, raw.h, bar)) return List.of();
        var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var trial = new ArrayList<ScoreNoteEvent>(18);
        var hooks = new ArrayList<Dyad>();
        var terminal = thirdsDyad(ordered.subList(16, 18));
        for (var plane : List.of(raw, normalized)) {
            if (sharedUpAxis(plane, terminal.upper, terminal.lower, gap,
                    new PrintedBeamShafts.Budget(55_000)) == null
                    || !hollowDyadHead(plane, terminal.upper, gap)
                    || !hollowDyadHead(plane, terminal.lower, gap)) return List.of();
        }
        for (int i = 0; i < ordered.size(); i += 2) {
            var pair = thirdsDyad(ordered.subList(i, i + 2));
            boolean half = i == 16;
            boolean dotted = !half && i % 4 == 0;
            int depth = pair.lower.note.beamCount();
            boolean missing = !dotted && !half && depth == 2;
            if (Math.abs(pair.lower.x - pair.upper.x) > gap * .22f
                    || pair.upper.note.staffStep() - pair.lower.note.staffStep() != 5
                    || i > 0 && (pair.lower.x - ordered.get(i - 2).x < gap * 1.5f
                            || pair.lower.x - ordered.get(i - 2).x > gap * 3.6f)) return List.of();
            for (var owner : ordered.subList(i, i + 2)) {
                var n = owner.note;
                if (!regularMarkings(n, 1) || n.stemDirection() != 1
                        || n.augmentationDots() != (dotted ? 1 : 0)
                        || n.unbeamedDurationBeats() != (half ? 2 : 0)
                        || n.beamCount() != (half ? 0 : dotted || missing ? 2 : 3)) return List.of();
                if (!half) {
                    float next = ordered.get(i + 2).x;
                    if (dot(raw, owner, next, gap) != dotted || dot(normalized, owner, next, gap) != dotted)
                        return List.of();
                }
                trial.add(missing ? rhythm(n, false) : n);
            }
            if (missing) {
                var peer = thirdsDyad(ordered.subList(i - 2, i));
                if (!coveredUpwardHook(raw, pair, peer, gap) || !coveredUpwardHook(normalized, pair, peer, gap))
                    return List.of();
                hooks.add(pair);
            }
        }
        if (hooks.isEmpty() || hooks.size() > 2) return List.of();
        for (int i = 0; i < trial.size(); i++) {
            int slot = i / 2;
            boolean dotted = slot % 2 == 0;
            var place = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4).orElse(null);
            if (place == null || place.onsetBeats() != slot / 2 * .5 + (dotted ? 0 : .375)
                    || place.durationBeats() != (slot == 8 ? 2 : dotted ? .375 : .125)) return List.of();
        }
        return hooks;
    }

    private static boolean coveredUpwardHook(Ink ink, Dyad owner, Dyad peer, float gap) {
        if (ascendingSharedThirdHook(ink, peer.upper, peer.lower, owner.upper, owner.lower, gap)) return true;
        var budget = new PrintedBeamShafts.Budget(65_000);
        var a = sharedUpAxis(ink, owner.upper, owner.lower, gap, budget);
        var b = sharedUpAxis(ink, peer.upper, peer.lower, gap, budget);
        return a != null && b != null && !budget.refused()
                && concentratedBeamlet(ink, new Shaft(a[0], a[1]), new Shaft(b[0], b[1]), gap, -1);
    }

    private static boolean hollowDyadHead(Ink ink, Head head, float gap) {
        int x0 = Math.round(head.x - gap), x1 = Math.round(head.x + gap * .63f);
        int y0 = Math.round(head.y - gap * .73f), y1 = Math.round(head.y + gap * .73f);
        return x0 >= 0 && y0 >= 0 && x1 < ink.w && y1 < ink.h
                && WholeHeadPocketEvidence.hasEnclosedOpening(ink.pixels, ink.w, ink.h, x0, y0, x1, y1,
                        head.x - gap * .12f, head.y, gap, Math.max(12, Math.round(gap * gap * .065f)), 3);
    }

    private record ClosedWideRibbon(Head alias, List<Head> heads) {}

    private static ClosedWideRibbon closedDownstemOpeningTriple(
            Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23
                || !hasClosingRule(raw.pixels, raw.w, raw.h, bar)
                || !hasClosingRule(normalized.pixels, raw.w, raw.h, bar)) return null;
        var aliases = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (aliases.size() != 1) return null;
        var alias = aliases.get(0);
        if (!regular(alias.note) || alias.note.writtenAccidental() != 2 || alias.note.articulations() != 0
                || alias.note.beamCount() != 1 || alias.note.augmentationDots() != 1) return null;
        var sorted = row.stream().filter(h -> h != alias).sorted(Comparator.comparingDouble(Head::x)).toList();
        var opening = thirdsDyad(sorted.subList(0, 2));
        var next = thirdsDyad(sorted.subList(2, 4));
        if (Math.abs(opening.lower.x - alias.x) > gap * .18f || alias.y - opening.lower.y < gap * 3.2f)
            return null;
        for (var plane : List.of(raw, normalized))
            if (!coveredOpeningDyadBeam(plane, opening, next, alias, gap)) return null;

        var trial = new ArrayList<ScoreNoteEvent>(24);
        Dyad prior = null;
        int missedHooks = 0;
        int missedDots = 0;
        for (int i = 0; i < sorted.size(); i += 2) {
            var pair = thirdsDyad(sorted.subList(i, i + 2));
            boolean dotted = i % 4 == 0;
            int depth = pair.lower.note.beamCount();
            int dots = pair.lower.note.augmentationDots();
            boolean missedHook = !dotted && depth == 2;
            boolean missedDot = dotted && dots == 0;
            if (Math.abs(pair.lower.x - pair.upper.x) > gap * .24f
                    || pair.upper.note.staffStep() - pair.lower.note.staffStep() != 5
                    || prior != null && (pair.lower.x - prior.lower.x < gap * 2f
                            || pair.lower.x - prior.lower.x > gap * 3.6f)) return null;
            if (dotted ? depth != (i == 0 ? 1 : 2) : dots != 0 || depth < 2 || depth > 3) return null;
            float following = i + 2 < sorted.size() ? sorted.get(i + 2).x : bar.right() * raw.w;
            for (var h : sorted.subList(i, i + 2)) {
                var n = h.note;
                if (!regular(n, 1, true) || n.stemDirection() != -1
                        || n.beamCount() != depth || n.augmentationDots() != dots) return null;
                trial.add(rhythm(n, dotted));
                for (var plane : List.of(raw, normalized))
                    if (dot(plane, h, following, gap) != dotted) return null;
            }
            for (var plane : List.of(raw, normalized)) {
                if (missedHook && !concentratedDownwardHook(plane, pair, prior, gap)) return null;
            }
            if (missedHook) missedHooks++;
            if (missedDot) missedDots++;
            prior = pair;
        }
        if (missedHooks < 1 || missedHooks > 3 || missedDots != 1
                || !fillsDottedClock(trial, 3)) return null;
        return new ClosedWideRibbon(alias, sorted);
    }

    private static boolean coveredOpeningDyadBeam(Ink ink, Dyad first, Dyad second, Head alias, float gap) {
        var budget = new PrintedBeamShafts.Budget(90_000);
        if (!PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, alias.x, alias.y, gap, budget).isEmpty()
                || dot(ink, alias, second.lower.x, gap)) return false;
        var opening = commonShafts(ink, first, gap, budget);
        var following = commonShafts(ink, second, gap, budget);
        for (var a : opening) {
            for (var b : following) {
                float slope = (b.tip - a.tip) / (float) (b.x - a.x);
                if (slope < .05f || slope > .28f || alias.x - a.x < gap * .45f
                        || alias.x - a.x > gap * .85f) continue;
                int outer = Math.round(a.tip - gap * .24f);
                int center = Math.round(alias.y);
                int x = Math.round(alias.x);
                if (ink.weight(x, center, 2) != 5 || ink.weight(x - 4, center, 2) != 5
                        || ink.weight(x + 7, center, 2) != 5) continue;
                for (int depth = Math.round(gap * .62f); depth <= Math.round(gap * 1.05f); depth++) {
                    int both = 0, separated = 0;
                    for (int column = a.x + 3; column <= b.x - 3; column++) {
                        int y = Math.round(outer + slope * (column - a.x));
                        if (ink.weight(column, y, 1) != 3 || ink.weight(column, y - depth, 1) != 3)
                            continue;
                        both++;
                        for (int seam = y - depth + 3; seam <= y - 3; seam++) {
                            if (!ink.dark(column, seam)) {
                                separated++;
                                break;
                            }
                        }
                    }
                    if (both >= (b.x - a.x - 5) * .92f && separated >= .55f * gap) return true;
                }
            }
        }
        return false;
    }

    private static boolean concentratedDownwardHook(Ink ink, Dyad pair, Dyad preceding, float gap) {
        if (provesThirdsHookWithQuarterTail(ink, pair, preceding, gap)) return true;
        var budget = new PrintedBeamShafts.Budget(90_000);
        var shafts = commonShafts(ink, pair, gap, budget);
        var previous = commonShafts(ink, preceding, gap, budget);
        for (var own : shafts)
            for (var peer : previous)
                if (concentratedBeamlet(ink, own, peer, gap, 1)) return true;
        return false;
    }

    private static boolean concentratedBeamlet(Ink ink, Shaft own, Shaft peer, float gap, int direction) {
        float slope = (own.tip - peer.tip) / (float) (own.x - peer.x);
        if (slope < 0 || slope > .25f) return false;
        int radius = Math.round(gap * .30f);
        for (int depth = Math.round(gap * 1.70f); depth <= Math.round(gap * 2.35f); depth++) {
            int inside = 0, outside = 0;
            for (int dx = Math.round(.40f * gap); dx <= Math.round(.77f * gap); dx++) {
                int y = Math.round(own.tip - direction * depth - dx * slope);
                if (ink.weight(own.x - dx, y, radius) >= gap * .42f) inside++;
            }
            for (int dx = Math.round(1.05f * gap); dx <= Math.round(1.35f * gap); dx++) {
                int y = Math.round(own.tip - direction * depth - dx * slope);
                if (ink.weight(own.x - dx, y, radius) >= gap * .38f) outside++;
            }
            if (inside >= gap * .30f && outside <= 1) return true;
        }
        return false;
    }

    private static ThirdsClock closedThirdsClock(
            Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23
                || !hasClosingRule(raw.pixels, raw.w, raw.h, bar)
                || !hasClosingRule(normalized.pixels, raw.w, raw.h, bar)) return null;
        var masks = row.stream().filter(head -> head.note.stemDirection() == 0).toList();
        if (masks.size() != 1) return null;
        var mask = masks.get(0);
        if (mask.note.beamCount() != 3 || mask.note.augmentationDots() != 0
                || mask.note.unbeamedDurationBeats() != 0 || mask.note.writtenAccidental() != 2) return null;
        var ordered = row.stream().filter(h -> h != mask).sorted(Comparator.comparingDouble(Head::x)).toList();
        var columns = new ArrayList<List<Head>>();
        for (var note : ordered) {
            if (columns.isEmpty() || note.x - columns.get(columns.size() - 1).get(0).x > .24f * gap)
                columns.add(new ArrayList<>());
            columns.get(columns.size() - 1).add(note);
        }
        if (columns.size() != 16 || columns.get(0).size() != 2 || columns.get(1).size() != 2
                || columns.stream().anyMatch(col -> col.size() > 2)) return null;
        var trial = new ArrayList<ScoreNoteEvent>(ordered.size());
        var marked = new ArrayList<Integer>();
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            if (i > 0) {
                float spacing = col.get(0).x - columns.get(i - 1).get(0).x;
                if (spacing < 1.44f * gap || spacing > 3.8f * gap) return null;
            }
            if (col.size() == 2 && Math.abs(col.get(0).note.staffStep() - col.get(1).note.staffStep()) > 3)
                return null;
            boolean dotted = i % 2 == 0;
            for (var h : col) {
                var n = h.note;
                int expected = i == 1 || dotted ? 2 : 3;
                if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                        || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.stemDirection() != -1
                        || n.unbeamedDurationBeats() != 0 || n.tupletDivisor() != 1
                        || n.beamCount() != expected || n.augmentationDots() != (dotted ? 1 : 0)
                        || n.tiedFromPrevious() || n.boundaryTies() != 0 || n.crossStaffBeam()
                        || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                        || (n.articulations() & NoteOrnament.GRACE) != 0) return null;
                trial.add(i == 1 ? rhythm(n, false) : n);
                marked.add(i);
            }
        }
        var first = thirdsDyad(columns.get(0));
        var shortPair = thirdsDyad(columns.get(1));
        if (!provesRestoredHook(raw, shortPair, first, gap, new PrintedBeamShafts.Budget(90_000))
                || !provesRestoredHook(normalized, shortPair, first, gap, new PrintedBeamShafts.Budget(90_000)))
            return null;
        var anchors = columns.stream().filter(col -> col.size() == 2)
                .map(ClosedDottedSixteenths::thirdsDyad)
                .filter(d -> Math.abs(d.lower.x - mask.x) <= gap * .22f
                        && mask.y - d.lower.y > gap * 3.8f).toList();
        if (anchors.size() != 1 || !embeddedRailMask(raw, anchors.get(0), mask, gap)
                || !embeddedRailMask(normalized, anchors.get(0), mask, gap)) return null;
        for (int i = 0; i < trial.size(); i++) {
            int attack = marked.get(i);
            boolean dotted = attack % 2 == 0;
            var placement = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != attack / 2 * .5 + (dotted ? 0 : .375)
                    || placement.durationBeats() != (dotted ? .375 : .125)) return null;
        }
        return new ThirdsClock(List.of(mask), List.of(shortPair));
    }

    private static ThirdsClock closedThirdsWithQuarterTail(
            Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23
                || !hasClosingRule(raw.pixels, raw.w, raw.h, bar)
                || !hasClosingRule(normalized.pixels, raw.w, raw.h, bar)) return null;
        var impostors = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
        if (impostors.size() != 1) return null;
        var mask = impostors.get(0);
        if (mask.note.beamCount() != 0 || mask.note.unbeamedDurationBeats() != 1
                || mask.note.augmentationDots() != 0 || mask.note.writtenAccidental() != 2
                || mask.note.articulations() != 0) return null;
        var sorted = row.stream().filter(h -> h != mask).sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(13);
        var replacements = new ArrayList<Dyad>();
        var trial = new ArrayList<ScoreNoteEvent>(26);
        for (int i = 0; i < sorted.size(); i += 2) {
            var pair = thirdsDyad(sorted.subList(i, i + 2));
            boolean finalQuarter = i == 24;
            boolean dotted = !finalQuarter && i % 4 == 0;
            boolean restored = !dotted && !finalQuarter && pair.upper.note.beamCount() == 2;
            if (Math.abs(pair.upper.x - pair.lower.x) > gap * .25f
                    || pair.upper.note.staffStep() - pair.lower.note.staffStep() < 2
                    || pair.upper.note.staffStep() - pair.lower.note.staffStep() > 3
                    || i > 0 && (pair.lower.x - pairs.get(pairs.size() - 1).lower.x < gap * 1.40f
                            || pair.lower.x - pairs.get(pairs.size() - 1).lower.x > gap * 4f)) return null;
            float next = finalQuarter ? bar.right() * raw.w : sorted.get(i + 2).x;
            for (var h : sorted.subList(i, i + 2)) {
                var n = h.note;
                int beams = finalQuarter ? 0 : dotted || restored ? 2 : 3;
                if (n.kind() != ScoreNoteEvent.Kind.PITCHED || n.staffIndex() != 0 || n.staffCount() != 1
                        || n.visualSize() != ScoreNoteEvent.VisualSize.FULL || n.stemDirection() != -1
                        || n.unbeamedDurationBeats() != (finalQuarter ? 1 : 0) || n.tupletDivisor() != 1
                        || n.beamCount() != beams || n.augmentationDots() != (dotted ? 1 : 0)
                        || n.boundaryTies() != 0 || n.crossStaffBeam()
                        || n.leadingRestBeats() != 0 || n.followingRestBeats() != 0
                        || (n.articulations() & NoteOrnament.GRACE) != 0) return null;
                trial.add(restored ? rhythm(n, false) : n);
            }
            for (var ink : List.of(raw, normalized)) {
                boolean lowDot = dot(ink, pair.lower, next, gap);
                boolean upDot = dot(ink, pair.upper, next, gap);
                if (dotted ? !lowDot && !upDot : lowDot && upDot || finalQuarter && (lowDot || upDot))
                    return null;
            }
            if (restored) {
                var previous = pairs.get(pairs.size() - 1);
                if (!provesThirdsHookWithQuarterTail(raw, pair, previous, gap)
                        || !provesThirdsHookWithQuarterTail(normalized, pair, previous, gap))
                    return null;
                replacements.add(pair);
            }
            pairs.add(pair);
        }
        if (replacements.isEmpty() || replacements.size() > 3
                || !reversedHookBlob(raw, pairs.get(1), mask, gap)
                || !reversedHookBlob(normalized, pairs.get(1), mask, gap)
                || commonShafts(raw, pairs.get(12), gap, new PrintedBeamShafts.Budget(90_000)).isEmpty()
                || commonShafts(normalized, pairs.get(12), gap, new PrintedBeamShafts.Budget(90_000)).isEmpty())
            return null;
        for (int i = 0; i < trial.size(); i++) {
            int attack = i / 2;
            boolean dotted = attack % 2 == 0;
            var placement = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4).orElse(null);
            if (placement == null || placement.onsetBeats() != attack / 2 * .5 + (dotted ? 0 : .375)
                    || placement.durationBeats() != (i >= 24 ? 1 : dotted ? .375 : .125)) return null;
        }
        return new ThirdsClock(List.of(mask), List.copyOf(replacements));
    }

    private static ThirdsClock closedOctavesClock(Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23
                || !hasClosingRule(raw.pixels, raw.w, raw.h, bar)
                || !hasClosingRule(normalized.pixels, raw.w, raw.h, bar)) return null;
        var masks = row.stream().filter(h -> h.note.stemDirection() != -1).toList();
        if (masks.size() != 4 || masks.stream().filter(h -> h.note.stemDirection() == 0).count() != 1
                || masks.stream().anyMatch(h -> h.note.writtenAccidental() != 2 || h.note.augmentationDots() != 0
                        || h.note.articulations() != 0 || h.note.beamCount() > 1)) return null;
        var ordered = row.stream().filter(h -> h.note.stemDirection() == -1)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        var dyads = new ArrayList<Dyad>(16);
        var trial = new ArrayList<ScoreNoteEvent>(32);
        var restored = new ArrayList<Dyad>();
        for (int i = 0; i < ordered.size(); i += 2) {
            var pair = thirdsDyad(ordered.subList(i, i + 2));
            var prior = i == 0 ? null : dyads.get(dyads.size() - 1);
            boolean dotted = i % 4 == 0;
            boolean missedHook = !dotted && pair.lower.note.beamCount() == 2;
            if (Math.abs(pair.lower.x - pair.upper.x) > gap * .23f
                    || pair.upper.note.staffStep() - pair.lower.note.staffStep() != 7
                    || prior != null && (pair.lower.x - prior.lower.x < gap * 2f
                            || pair.lower.x - prior.lower.x > gap * 4f)) return null;
            float next = i + 2 == ordered.size() ? bar.right() * raw.w : ordered.get(i + 2).x;
            for (var h : ordered.subList(i, i + 2)) {
                var note = h.note;
                if (!regular(note, 1, true) || note.augmentationDots() != (dotted ? 1 : 0)
                        || note.beamCount() != (dotted || missedHook ? 2 : 3)) return null;
                trial.add(missedHook ? rhythm(note, false) : note);
            }
            for (var ink : List.of(raw, normalized)) {
                boolean low = dot(ink, pair.lower, next, gap), up = dot(ink, pair.upper, next, gap);
                if (dotted ? !low && !up : low && up) return null;
                if (missedHook && !provesOctaveHook(ink, pair, prior, masks, gap)) return null;
            }
            if (missedHook) restored.add(pair);
            dyads.add(pair);
        }
        if (restored.isEmpty()) return null;
        for (var mask : masks) {
            var following = dyads.stream().filter(p -> p.lower.x > mask.x).findFirst().orElse(null);
            if (following == null) return null;
            if (mask.note.stemDirection() == 0) {
                int j = dyads.indexOf(following);
                if (j == 0 || !insideDottedBeam(raw, dyads.get(j - 1), following, mask, gap)
                        || !insideDottedBeam(normalized, dyads.get(j - 1), following, mask, gap)) return null;
            } else if (!reversedHookBlob(raw, following, mask, gap)
                    || !reversedHookBlob(normalized, following, mask, gap)) return null;
        }
        for (int i = 0; i < trial.size(); i++) {
            int attack = i / 2;
            boolean dotted = attack % 2 == 0;
            var place = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4).orElse(null);
            if (place == null || place.onsetBeats() != attack / 2 * .5 + (dotted ? 0 : .375)
                    || place.durationBeats() != (dotted ? .375 : .125)) return null;
        }
        return new ThirdsClock(masks, List.copyOf(restored));
    }

    private static boolean provesOctaveHook(Ink ink, Dyad pair, Dyad prior, List<Head> masks, float gap) {
        if (provesThirdsHookWithQuarterTail(ink, pair, prior, gap)) return true;
        var budget = new PrintedBeamShafts.Budget(90_000);
        if (OwnedTertiaryBeamlets.provesJoinedClosingDyadHook(ink.pixels, ink.w, ink.h,
                pair.lower.x, pair.lower.y, pair.upper.x, pair.upper.y,
                prior.upper.x, prior.upper.y, prior.lower.x, prior.lower.y, gap, budget)) return true;
        return !commonShafts(ink, pair, gap, budget).isEmpty() && !budget.refused()
                && masks.stream().anyMatch(mask -> mask.note.stemDirection() == 1
                        && reversedHookBlob(ink, pair, mask, gap));
    }

    private static boolean insideDottedBeam(Ink ink, Dyad before, Dyad after, Head mask, float gap) {
        if (mask.note.beamCount() != 0 || mask.note.unbeamedDurationBeats() != 1
                || mask.y < before.lower.y + gap * 2.5f) return false;
        var budget = new PrintedBeamShafts.Budget(90_000);
        var left = commonShafts(ink, before, gap, budget);
        var right = commonShafts(ink, after, gap, budget);
        for (var a : left) {
            for (var b : right) {
                float slope = (b.tip - a.tip) / (float) (b.x - a.x);
                var center = a.tip + slope * (mask.x - a.x);
                if (slope <= 0 || slope > .3f || mask.x - a.x < gap || b.x - mask.x < gap
                        || center - mask.y < gap * .42f || center - mask.y > gap * .93f) continue;
                int radius = Math.round(gap * .62f);
                int supported = 0;
                for (int x = Math.round(mask.x) - radius; x <= Math.round(mask.x) + radius; x++) {
                    int y = Math.round(mask.y + (x - mask.x) * slope);
                    int dark = 0;
                    for (int dy = -2; dy <= 2; dy++) if (ink.dark(x, y + dy)) dark++;
                    if (dark >= 3) supported++;
                }
                if (supported >= radius * 1.7f) return true;
            }
        }
        return false;
    }

    private static boolean provesThirdsHookWithQuarterTail(Ink ink, Dyad pair, Dyad previous, float gap) {
        var budget = new PrintedBeamShafts.Budget(90_000);
        if (provesRestoredHook(ink, pair, previous, gap, budget)) return true;
        return !commonShafts(ink, pair, gap, budget).isEmpty() && !budget.refused()
                && OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(ink.pixels, ink.w, ink.h,
                        pair.lower.x, pair.lower.y, previous.lower.x, previous.lower.y, gap);
    }

    private static boolean reversedHookBlob(Ink ink, Dyad next, Head mask, float gap) {
        float distance = next.lower.x - mask.x;
        if (distance < gap * .9f || distance > gap * 1.55f || mask.y - next.lower.y < gap * 2f)
            return false;
        var budget = new PrintedBeamShafts.Budget(95_000);
        var axes = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, mask.x, mask.y, gap, budget);
        if (axes.size() != 1 || axes.get(0).direction() != -1
                || Math.abs(axes.get(0).endY() - next.upper.y) > gap * .32f) return false;
        var arm = axes.get(0);
        int x = Math.round(mask.x);
        if (arm.left() - x < gap * .35f || arm.left() - x > gap * .75f
                || next.lower.x - arm.left() < gap * .45f || next.lower.x - arm.left() > gap * .9f)
            return false;
        int row = Math.round(mask.y);
        int shortRows = 0;
        for (int y = row - Math.round(gap * .34f); y <= row + Math.round(gap * .30f); y++) {
            int dark = 0;
            for (int column = x; column <= arm.left(); column++) if (ink.dark(column,y)) dark++;
            if (dark >= (arm.left() - x + 1) * .90f) shortRows++;
        }
        return shortRows >= gap * .42f;
    }

    private static Dyad thirdsDyad(List<Head> heads) {
        var a = heads.get(0);
        var b = heads.get(1);
        return a.y < b.y ? new Dyad(a, b) : new Dyad(b, a);
    }

    private static boolean embeddedRailMask(Ink ink, Dyad owner, Head mask, float gap) {
        var budget = new PrintedBeamShafts.Budget(95_000);
        if (!PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, mask.x, mask.y, gap, budget).isEmpty())
            return false;
        for (var shaft : commonShafts(ink, owner, gap, budget)) {
            if (mask.x - shaft.x < gap * .35f || mask.x - shaft.x > gap * .85f
                    || shaft.tip - mask.y > gap * .9f || shaft.tip - mask.y < gap * .35f) continue;
            int top = Math.round(mask.y - gap * .5f);
            int bottom = Math.round(mask.y + gap * .45f);
            int far = Math.round(mask.x + gap * 1.15f);
            int rows = 0;
            for (int y = top; y <= bottom; y++) {
                int dark = 0;
                for (int x = shaft.x; x <= far; x++) if (ink.dark(x,y)) dark++;
                if (dark >= (far - shaft.x + 1) * .90f) rows++;
            }
            if (rows >= gap * .45f) return true;
        }
        return false;
    }

    static ScorePageInterpretation applyOpeningWideDyadHooks(
            ScorePageInterpretation score, byte[] gray, byte[] original, int width, int height,
            int physicalPage) {
        if (gray == null || original == null || width < 256 || height < 128
                || gray.length != (long) width * height || gray.length != original.length) return score;
        var rows = groupedHeads(score, width, height);
        var normalized = new Ink(gray, width, height);
        var source = new Ink(original, width, height);
        List<ScoreNoteEvent> repaired = score.notes();
        var beams = score.beamSource();
        for (var meter : score.meterChanges()) {
            int index = meter.measureIndex();
            int measureBeats = meter.numerator();
            if (index < 1 || index >= rows.size() || (measureBeats != 3 && measureBeats != 4)
                    || meter.denominator() != 4 || rows.get(index).size() != measureBeats * 8
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var bar = score.measures().get(index);
            var previous = score.measures().get(index - 1);
            if (bar.cadenceQuarterBeats() != 0 || bar.top() <= previous.bottom()) continue;
            var heads = rows.get(index).stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var trial = openingWideDyadHooks(normalized, source, bar, heads, measureBeats);
            if (trial.isEmpty()) continue;
            if (repaired == score.notes()) repaired = new ArrayList<>(repaired);
            for (int i = 0; i < trial.size(); i++) repaired.set(heads.get(i).index, trial.get(i));
            if (measureBeats == 4)
                beams = captureRestoredDyadBeams(beams, repaired, heads.stream().map(Head::index).toList(),
                        bar, gray, original, width, height, physicalPage);
        }
        if (repaired == score.notes()) return score;
        return score.withBeamSource(beams).withNotes(repaired,
                java.util.stream.IntStream.range(0, repaired.size()).boxed().toList());
    }

    static ScoreBeamSource captureRestoredDyadBeams(ScoreBeamSource beams, List<ScoreNoteEvent> notes,
            List<Integer> ordered, MeasureRegion bar, byte[] gray, byte[] original,
            int width, int height, int physicalPage) {
        if (physicalPage < 0 || ordered.size() != 32) return beams;
        float left = bar.left() * width, span = (bar.right() - bar.left()) * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        var attacks = new ArrayList<ClosedOctaveBeamSource.Attack>(16);
        var directions = new ArrayList<Integer>(16);
        for (int i = 0; i < ordered.size(); i += 2) {
            int upper = ordered.get(i), lower = ordered.get(i + 1);
            if (notes.get(upper).pageY() > notes.get(lower).pageY()) {
                upper = ordered.get(i + 1);
                lower = ordered.get(i);
            }
            var a = notes.get(upper);
            var b = notes.get(lower);
            attacks.add(new ClosedOctaveBeamSource.Attack(upper, lower,
                    left + a.positionInMeasure() * span, a.pageY() * height,
                    left + b.positionInMeasure() * span, b.pageY() * height));
            directions.add(-a.stemDirection());
        }
        return ClosedOctaveBeamSource.captureDetachedDottedDyads(
                beams, physicalPage, gray, original, width, height, gap, attacks, directions);
    }

    private static List<ScoreNoteEvent> openingWideDyadHooks(
            Ink gray, Ink source, MeasureRegion bar, List<Head> heads, int measureBeats) {
        float gap = (bar.bottom() - bar.top()) * gray.h / 8.2f;
        float right = bar.right() * gray.w;
        float lastHead = heads.get(heads.size() - 1).x;
        if (gap < 10 || gap > 27 || right - lastHead < gap * .75f || right - lastHead > gap * 2
                || !independentUpstemClosingRule(gray, bar, lastHead, gap)
                || !independentUpstemClosingRule(source, bar, lastHead, gap)) return List.of();
        var trial = new ArrayList<ScoreNoteEvent>(heads.size());
        boolean falseDottedPair = false;
        int corrections = 0;
        int interval = Math.abs(heads.get(0).note.staffStep() - heads.get(1).note.staffStep());
        if (interval < 4 || interval > 6) return List.of();
        for (int i = 0; i < heads.size(); i += 2) {
            var left = heads.get(i);
            var rightHead = heads.get(i + 1);
            boolean dotted = i % 4 == 0;
            float next = i + 2 < heads.size() ? heads.get(i + 2).x : right;
            if (rightHead.x - left.x > gap * .24f || Math.abs(left.y - rightHead.y) < gap * 1.6f
                    || Math.abs(left.note.staffStep() - rightHead.note.staffStep()) != interval
                    || i + 2 < heads.size() && (next - rightHead.x < gap * 1.38f || next - rightHead.x > gap * 3.6f))
                return List.of();
            for (var owner : List.of(left, rightHead)) {
                if (!regular(owner.note, 1, measureBeats == 3) || owner.note.stemDirection() != 1
                        || owner.note.beamCount() < 2
                        || owner.note.clefBottomDiatonic() != heads.get(0).note.clefBottomDiatonic()
                        || measureBeats == 4
                                && (dot(gray, owner, next, gap) != dotted || dot(source, owner, next, gap) != dotted))
                    return List.of();
            }
            if (measureBeats == 3 && dotted
                    && ((!dot(gray, left, next, gap) && !dot(gray, rightHead, next, gap))
                    || (!dot(source, left, next, gap) && !dot(source, rightHead, next, gap)))) return List.of();
            if (measureBeats == 3 && !dotted
                    && (left.note.augmentationDots() != 0 || rightHead.note.augmentationDots() != 0
                    || dot(gray, left, next, gap) && dot(gray, rightHead, next, gap)
                    || dot(source, left, next, gap) && dot(source, rightHead, next, gap))) return List.of();
            int depth = dotted ? 2 : 3, dots = dotted ? 1 : 0;
            if (left.note.beamCount() == depth && rightHead.note.beamCount() == depth
                    && left.note.augmentationDots() == dots && rightHead.note.augmentationDots() == dots) {
                trial.add(left.note);
                trial.add(rightHead.note);
                continue;
            }
            if (dotted || ++corrections > (measureBeats == 3 ? 3 : 2)
                    || left.note.beamCount() != 2 || rightHead.note.beamCount() != 2
                    || left.note.augmentationDots() != rightHead.note.augmentationDots()
                    || !ascendingSharedThirdHook(gray, heads.get(i - 2), heads.get(i - 1), left, rightHead, gap)
                    || !ascendingSharedThirdHook(source, heads.get(i - 2), heads.get(i - 1), left, rightHead, gap))
                return List.of();
            falseDottedPair |= left.note.augmentationDots() == 1;
            trial.add(rhythm(left.note, false));
            trial.add(rhythm(rightHead.note, false));
        }
        return (measureBeats == 3 ? corrections > 0 : falseDottedPair)
                && fillsDottedClock(trial, measureBeats) ? trial : List.of();
    }

    private static boolean ascendingSharedThirdHook(
            Ink ink, Head a, Head b, Head first, Head second, float gap) {
        var budget = new PrintedBeamShafts.Budget(40_000);
        var preceding = sharedUpAxis(ink, a, b, gap, budget);
        var owner = sharedUpAxis(ink, first, second, gap, budget);
        return preceding != null && owner != null && !budget.refused()
                && OwnedTertiaryBeamlets.ownsThirdRailAtUpStemEnd(
                        ink.pixels, ink.w, ink.h, owner, preceding, gap);
    }

    private static boolean independentUpstemClosingRule(Ink ink, MeasureRegion bar, float lastHead, float gap) {
        float left = bar.left() * ink.w, right = bar.right() * ink.w;
        var track = readOctaveStaff(ink, left, right, bar.bottom() * ink.h - 2.1f * gap, gap);
        if (track == null) return false;
        var rail = track.at(right);
        if (rail[0] - 4 * rail[1] < 1 || rail[0] >= ink.h) return false;
        var staff = new PlayingTechniqueDetector.Staff(rail[0] - 4 * rail[1], rail[0], rail[1], 0, 1);
        float hint = Math.max(right, lastHead + gap * 1.62f);
        int boundary = PrintedCadenceDurations.boundaryColumn(ink.pixels, ink.w, staff, hint, true, false);
        return boundary >= lastHead + gap * .92f && Math.abs(boundary - right) <= gap * .80f;
    }

    private static int[] sharedUpAxis(
            Ink ink, Head a, Head b, float gap, PrintedBeamShafts.Budget budget) {
        var first = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, a.x, a.y, gap, budget);
        var second = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, b.x, b.y, gap, budget);
        if (first.size() != 1 || second.size() != 1
                || first.get(0).direction() != -1 || second.get(0).direction() != -1) return null;
        var left = first.get(0);
        var right = second.get(0);
        int low = Math.max(left.left(), right.left()), high = Math.min(left.right(), right.right());
        if (low > high || Math.abs(left.endY() - right.endY()) > 1) return null;
        return new int[] {(low + high) / 2, (left.endY() + right.endY()) / 2};
    }

    static ScorePageInterpretation applyClosedMonophonicHooks(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, int w, int h) {
        return applyClosedMonophonicHooks(score, inherited, gray, null, w, h);
    }

    static ScorePageInterpretation applyClosedMonophonicHooks(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] gray,
            byte[] raw,
            int w,
            int h) {
        if (gray == null || gray.length != (long) w * h || w < 256 || h < 128) return score;
        var ink = new Ink(gray, w, h);
        var bars = groupedHeads(score, w, h);
        var notes = score.notes();
        var corrected = new ArrayList<>(notes);
        var meter = inherited;
        int nextMeter = 0;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            final int bar = i;
            if (meter == null
                    || meter.denominator() != 4
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == bar))
                continue;
            var region = score.measures().get(i);
            int expected = 4 * meter.numerator();
            var heads = bars.get(i);
            if ((meter.numerator() == 3 || meter.numerator() == 4)
                    && (heads.size() == expected || heads.size() == expected + 1)
                    && raw != null && raw.length == gray.length) {
                var original = new Ink(raw, w, h);
                var order = new ArrayList<>(heads);
                order.sort(Comparator.comparingDouble(Head::x));
                Head alias = null;
                boolean digitAlias = false;
                if (order.size() > expected) {
                    alias = provedInnerBeamAlias(original, ink, region, order);
                    if (alias == null) {
                        alias = terminalDottedPairAlias(order, region, h);
                        digitAlias = alias != null;
                    }
                    if (alias != null) order.remove(alias);
                }
                if (order.size() == expected) {
                    var repaired = repairVerifiedDottedPairs(original, ink, region, order, digitAlias);
                    if (repaired != null && digitAlias
                            && readDottedPairTerminal(score, original, ink, region, order, alias, List.of()) < 1)
                        repaired = null;
                    if (repaired != null) {
                        if (alias != null) corrected.set(alias.index, null);
                        for (int slot = 0; slot < order.size(); slot++)
                            corrected.set(order.get(slot).index, repaired.get(slot));
                        continue;
                    }
                }
            }
            if ((meter.numerator() == 3 || meter.numerator() == 4)
                    && heads.size() > expected + 1 && heads.size() <= expected + 5
                    && raw != null && raw.length == gray.length) {
                var original = new Ink(raw, w, h);
                var owners = heads.stream().filter(hd -> hd.note.stemDirection() == -1)
                        .sorted(Comparator.comparingDouble(Head::x)).toList();
                if (owners.size() == expected && provesDownstemRibbonAliases(score, original, ink, region, heads, owners)) {
                    var repaired = repairVerifiedDottedPairs(original, ink, region, owners, true, true);
                    if (repaired != null) {
                        for (var head : heads)
                            if (head.note.stemDirection() != -1) corrected.set(head.index, null);
                        for (int slot = 0; slot < owners.size(); slot++)
                            corrected.set(owners.get(slot).index, repaired.get(slot));
                        continue;
                    }
                }
            }
            if (meter.numerator() != 4 || (bars.get(i).size() != 9 && bars.get(i).size() != 16)) continue;
            if (bars.get(i).size() == 9) {
                if (raw != null && raw.length == gray.length) {
                    var missing = dottedPairsBeforeHalf(new Ink(raw, w, h), ink, region, bars.get(i));
                    if (missing != null) corrected.set(missing.index, rhythm(missing.note, false));
                }
                continue;
            }
            float gap = (region.bottom() - region.top()) * h / 8.2f;
            float left = region.left() * w, right = region.right() * w;
            if (gap < 10
                    || gap > 27
                    || region.cadenceQuarterBeats() != 0
                    || right - left < gap * 32
                    || right - left > gap * 44) continue;
            var row = bars.get(i).stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            if (row.get(0).x - left > gap * 2.2f || right - row.get(15).x > gap * 2.7f) continue;
            var head = missingSingleHook(row, gap);
            if (head == null) {
                if (raw != null
                        && raw.length == gray.length
                        && provesOpposedDottedHookEnds(ink, new Ink(raw, w, h), region, row, gap)) {
                    for (int slot : new int[] {1, row.size() - 1}) {
                        var owner = row.get(slot);
                        corrected.set(owner.index, rhythm(owner.note, false));
                    }
                } else if (provesFourClosedDottedGroups(ink, region, row, gap)) {
                    for (int slot = 0; slot < row.size(); slot++) {
                        var owner = row.get(slot);
                        boolean dotted = slot % 2 == 0;
                        if (owner.note.beamCount() != (dotted ? 2 : 3)
                                || owner.note.augmentationDots() != (dotted ? 1 : 0))
                            corrected.set(owner.index, rhythm(owner.note, dotted));
                    }
                }
                continue;
            }
            int slot = row.indexOf(head);
            var prior = row.get(slot - 1);
            var terminal = row.get(15);
            float[] rules =
                    localRules(
                            ink,
                            right - gap * .72f,
                            terminal.y + terminal.note.staffStep() * gap * .5f,
                            gap);
            if (rules != null
                    && barline(ink, right, rules)
                    && OwnedTertiaryBeamlets.provesJoinedBackwardHook(
                            gray, w, h, head.x, head.y, prior.x, prior.y, rules[1]))
                corrected.set(head.index, rhythm(head.note, false));
        }
        if (corrected.equals(notes)) return score;
        var owners = IntStream.range(0, corrected.size()).filter(i -> corrected.get(i) != null).boxed().toList();
        return score.withNotes(owners.stream().map(corrected::get).toList(), owners);
    }

    /** Reconcile multiple false attacks only when every disputed head is owned by printed beam ink. */
    private static boolean provesDownstemRibbonAliases(
            ScorePageInterpretation score, Ink raw, Ink normalized, MeasureRegion bar,
            List<Head> heads, List<Head> owners) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (gap < 12 || gap > 24) return false;
        int clef = owners.get(0).note.clefBottomDiatonic();
        var claimedPairs = new java.util.HashMap<Integer, Head>();
        for (var alias : heads) {
            var n = alias.note;
            if (n.stemDirection() == -1) continue;
            if (!regularMarkings(n.withArticulations(n.articulations() & ~NoteOrnament.GRACE), 0)
                    || n.unbeamedDurationBeats() != 0 && !(n.stemDirection() == 1
                            && n.beamCount() == 0 && n.unbeamedDurationBeats() == 1)
                    || n.clefBottomDiatonic() != clef || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || n.beamCount() > 2
                    || (n.articulations() & ~NoteOrnament.GRACE) != 0
                    || n.augmentationDots() != 0) return false;
            if (n.stemDirection() == 0 && digitBelowPair(score, raw, normalized, bar, owners, alias, gap))
                continue;
            int pair = 0;
            while (pair < owners.size() && alias.x >= owners.get(pair + 1).x) pair += 2;
            if (pair == owners.size() || alias.x <= owners.get(pair).x) return false;
            var priorAlias = claimedPairs.putIfAbsent(pair, alias);
            if (priorAlias != null && (n.stemDirection() != 1 || priorAlias.note.stemDirection() != 1
                    || Math.abs(priorAlias.x - alias.x) > .32f * gap
                    || Math.abs(priorAlias.y - alias.y) < .87f * gap
                    || Math.abs(priorAlias.y - alias.y) > 1.34f * gap)) return false;
            var lead = owners.get(pair);
            var hook = owners.get(pair + 1);
            for (var ink : List.of(raw, normalized)) {
                var a = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, lead.x, lead.y, gap, -1);
                var b = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, hook.x, hook.y, gap, -1);
                if (a == null || b == null || !ribbonInkOwnsAlias(ink, alias, lead, hook, a, b, gap))
                    return false;
            }
        }
        return true;
    }

    private static boolean digitBelowPair(ScorePageInterpretation score, Ink original, Ink prepared,
            MeasureRegion bar, List<Head> owners, Head alias, float gap) {
        for (int i = 1; i < owners.size(); i += 2) {
            var owner = owners.get(i);
            if (Math.abs(alias.x - owner.x) > .30f * gap || alias.y - owner.y < 4 * gap
                    || alias.y - owner.y > 8 * gap) continue;
            return readDottedPairTerminal(score, original, prepared, bar, owners.subList(0, i + 1),
                    alias, List.of()) > 0;
        }
        return false;
    }

    private static boolean ribbonInkOwnsAlias(Ink ink, Head alias, Head lead, Head hook,
            PairedTremoloInk.Stem a, PairedTremoloInk.Stem b, float gap) {
        if (alias.x - a.x() < .55f * gap || alias.x > b.x() + 1
                || alias.y - Math.max(lead.y, hook.y) < (alias.note.stemDirection() == 1 ? 1.95f : 2.15f) * gap)
            return false;
        float slope = (b.endY() - a.endY()) / (float) (b.x() - a.x());
        if (Math.abs(slope) > .30f || !supportsPairRail(ink, a, b, slope, gap, .20f, .45f)
                || !supportsPairRail(ink, a, b, slope, gap, .94f, 1.34f)) return false;
        var shafts = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, alias.x, alias.y, gap,
                new PrintedBeamShafts.Budget(24_000));
        if (alias.note.stemDirection() == 1) {
            if (shafts.size() != 1 || shafts.get(0).direction() != -1
                    || b.x() < shafts.get(0).left() - 2 || b.x() > shafts.get(0).right() + 2
                    || Math.abs(shafts.get(0).endY() - hook.y) > .35f * gap) return false;
        } else if (!shafts.isEmpty()) return false;
        float depth = b.endY() + slope * (alias.x - b.x()) - alias.y;
        if (alias.note.stemDirection() == 1 && depth >= .35f * gap && depth <= 2.42f * gap
                && b.x() - alias.x >= .18f * gap && b.x() - alias.x < .55f * gap) {
            int x = Math.round(alias.x), y = Math.round(alias.y);
            for (int px = x; px <= b.x(); px++)
                if (ink.weight(px, y, 2) < 4) return false;
            return provesBackwardDottedHook(ink, hook, lead, gap);
        }
        if (depth >= .35f * gap && depth <= 1.43f * gap && b.x() - alias.x >= .55f * gap) {
            int radius = Math.round(.18f * gap), span = Math.round(.55f * gap), supported = 0;
            for (int dx = -span; dx <= span; dx++)
                if (ink.weight(Math.round(alias.x) + dx, Math.round(alias.y + slope * dx), radius)
                        >= 2 * radius) supported++;
            return supported >= 2 * span;
        }
        return depth >= 1.50f * gap && depth <= 2.62f * gap && b.x() - alias.x <= 1.03f * gap
                && compactDownstemHook(ink, a, b, gap, depth);
    }

    /** A thick inward lobe is attached to the right shaft and ends well before the left shaft. */
    private static boolean compactDownstemHook(Ink ink, PairedTremoloInk.Stem a, PairedTremoloInk.Stem b,
            float gap, float aliasDepth) {
        int span = b.x() - a.x();
        if (span < 1.9f * gap || span > 3.8f * gap) return false;
        float slope = (b.endY() - a.endY()) / (float) span;
        if (Math.abs(slope) > .30f || !supportsPairRail(ink, a, b, slope, gap, .20f, .45f)
                || !supportsPairRail(ink, a, b, slope, gap, .94f, 1.34f)) return false;
        int near = Math.round(.65f * gap), far = Math.round(1.42f * gap);
        for (int depth = Math.round(1.66f * gap); depth <= Math.round(2.42f * gap); depth++) {
            if (aliasDepth > 0 && Math.abs(aliasDepth - depth) > .34f * gap) continue;
            int hit = 0;
            for (int dx = 1; dx <= near; dx++) {
                int y = Math.round(b.endY() - slope * dx) - depth;
                if (ink.weight(b.x() - dx, y, 2) >= 4) hit++;
            }
            int outer = Math.round(b.endY() - slope * far) - depth;
            if (hit >= near - 1 && ink.weight(b.x() - far, outer, 2) == 0) return true;
        }
        return false;
    }

    private static boolean supportsPairRail(Ink ink, PairedTremoloInk.Stem a, PairedTremoloInk.Stem b,
            float slope, float gap, float shallow, float deep) {
        int margin = Math.round(.27f * gap);
        int left = a.x() + margin, right = b.x() - margin;
        if (right - left < gap) return false;
        for (int depth = Math.round(shallow * gap); depth <= Math.round(deep * gap); depth++) {
            int support = 0;
            for (int x = left; x <= right; x++) {
                int y = Math.round(b.endY() + slope * (x - b.x())) - depth;
                if (ink.weight(x, y, 1) == 3) support++;
            }
            if (support >= (right - left + 1) * .85f) return true;
        }
        return false;
    }

    private static Head provedInnerBeamAlias(Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (gap < 10 || gap > 27) return null;
        var stemless = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (stemless.size() != 1) return null;
        var alias = stemless.get(0);
        int slot = row.indexOf(alias);
        if (slot == 0 || slot + 1 >= row.size() || !regular(alias.note, 2)) return null;
        var owner = row.get(slot + 1);
        if (alias.note.beamCount() == 1 && alias.note.augmentationDots() == 0
                && alias.note.articulations() == 0 && slot % 2 == 1) {
            var lead = row.get(slot - 1);
            return provesMergedDownstemPair(raw, lead, alias, owner, gap)
                    && provesMergedDownstemPair(normalized, lead, alias, owner, gap) ? alias : null;
        }
        if (slot % 2 != 0 || alias.note.beamCount() != 3
                || (alias.note.articulations() & ~NoteArticulation.STACCATO) != 0) return null;
        if (owner.note.stemDirection() != -1 || owner.note.clefBottomDiatonic() != alias.note.clefBottomDiatonic())
            return null;
        var terminal = row.get(row.size() - 1);
        for (var source : List.of(raw, normalized)) {
            var rules = localRules(source, bar.right() * raw.w - gap * .72f,
                    terminal.y + terminal.note.staffStep() * gap * .5f, gap);
            if (rules == null) return null;
            var track = StaffPitchTrack.fromVerifiedSamples(List.of(
                    new float[] {owner.x - gap, rules[0], rules[1]},
                    new float[] {owner.x + gap, rules[0], rules[1]}));
            if (!shaftBandAlias(source, alias, owner, rules[1], track)
                    || !PrintedBeamShafts.at(source.pixels, source.w, source.h, alias.x, alias.y, rules[1],
                            new PrintedBeamShafts.Budget(20_000)).isEmpty()) return null;
        }
        return alias;
    }

    /** A thick rail can borrow an apparent head between the two printed shafts of a dotted pair. */
    private static boolean provesMergedDownstemPair(Ink ink, Head lead, Head alias, Head hook, float gap) {
        if (lead.note.stemDirection() != -1 || hook.note.stemDirection() != -1
                || lead.note.clefBottomDiatonic() != alias.note.clefBottomDiatonic()
                || hook.note.clefBottomDiatonic() != alias.note.clefBottomDiatonic()
                || lead.note.beamCount() != 2 || lead.note.augmentationDots() != 1
                || alias.y - Math.max(lead.y, hook.y) < 2.5f * gap
                || !PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, alias.x, alias.y, gap,
                        new PrintedBeamShafts.Budget(20_000)).isEmpty()) return false;
        var left = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, lead.x, lead.y, gap, -1);
        var right = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, hook.x, hook.y, gap, -1);
        return left != null && right != null && Math.abs(left.endY() - right.endY()) <= .6f * gap
                && liesInsideMergedRibbon(ink,
                        List.of(new Shaft(left.x(), left.endY()), new Shaft(right.x(), right.endY())), alias, gap)
                && WrappedTripletDotInk.singleAugmentationCore(
                        ink.pixels, ink.w, ink.h, lead.x, lead.y, hook.x, gap) != null
                && provesBackwardDottedHook(ink, hook, lead, gap);
    }

    /** A below-staff glyph can be misread as a stemless attack beneath a closing downstem. */
    private static Head terminalDottedPairAlias(List<Head> row, MeasureRegion bar, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 27) return null;
        var stemless = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (stemless.size() != 1) return null;
        var alias = stemless.get(0);
        int last = row.size() - 1;
        var terminal = row.get(row.get(last) == alias ? last - 1 : last);
        if ((row.get(last) != alias && row.get(last - 1) != alias)
                || !regular(alias.note, 2) || alias.note.beamCount() != 3
                || (alias.note.articulations() & ~NoteArticulation.STACCATO) != 0
                || terminal.note.stemDirection() != -1
                || alias.note.clefBottomDiatonic() != terminal.note.clefBottomDiatonic()
                || Math.abs(alias.x - terminal.x) > .35f * gap
                || alias.y - terminal.y < 4 * gap || alias.y - terminal.y > 9 * gap) return null;
        return alias;
    }

    /** Restore four complete groups from the printed connectors after the clock has been fixed. */
    static ScorePageInterpretation completeDottedQuartetBeams(ScorePageInterpretation score,
            ScoreMeterMap meter, byte[] raw, byte[] normalized, int w, int h) {
        if (raw == null || normalized == null || raw.length != (long) w * h
                || raw.length != normalized.length || score.beamSource().noteOrigins().size() != score.notes().size())
            return score;
        var original = new Ink(raw, w, h);
        var prepared = new Ink(normalized, w, h);
        var beams = score.beamSource();
        var complete = new java.util.HashSet<List<Integer>>();
        for (var g : beams.resolve().groups())
            if (g.columns().size() == 4 && g.columns().stream().allMatch(c -> c.usable() && c.owners().size() == 1)
                    && g.source().bridges().equals(List.of(3, 3, 3)) && g.source().hooks().size() == 2)
                complete.add(g.columns().stream().map(c -> c.owners().get(0)).toList());
        var rows = groupedHeads(score, w, h);
        for (int bar = 0; bar < rows.size(); bar++) {
            final int index = bar;
            if (meter.beatsInMeasure(bar) != 4 || rows.get(bar).size() != 16
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) continue;
            var row = rows.get(bar).stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var owners = row.stream().map(Head::index).toList();
            if (java.util.stream.IntStream.range(0, 4)
                    .allMatch(group -> complete.contains(owners.subList(4 * group, 4 * group + 4)))) continue;
            var region = score.measures().get(bar);
            var proof = repairVerifiedDottedPairs(original, prepared, region, row, true, true);
            if (proof == null || row.stream().anyMatch(hd -> hd.note.stemDirection() != -1)
                    || !proof.equals(row.stream().map(Head::note).toList())
                    || !dottedQuartetInk(original, region, row)
                    || raw != normalized && !dottedQuartetInk(prepared, region, row)) continue;
            beams = ClosedDottedHookCadence.projectRun(beams, owners, List.of(), -1, false);
        }
        return beams == score.beamSource() ? score : score.withBeamSource(beams);
    }

    private static boolean dottedQuartetInk(Ink ink, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        var budget = new PrintedBeamShafts.Budget(420_000);
        PrintedBeamShafts.Shaft previousAxis = null;
        PairedTremoloInk.Stem previousStem = null;
        for (int slot = 0; slot < row.size(); slot++) {
            var head = row.get(slot);
            var found = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, head.x, head.y, gap, budget);
            if (budget.refused() || found.size() != 1 || found.get(0).direction() != 1) return false;
            var axis = found.get(0);
            var stem = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, head.x, head.y, gap, -1);
            if (stem == null) return false;
            if (previousAxis != null) {
                if (slot % 4 == 0) {
                    if (!WrappedEighthTriplets.cutBetweenRecoveredGroups(
                            ink.pixels, ink.w, ink.h, bar, previousAxis, axis, gap)
                            && !staffOnlyQuartetGap(ink, previousStem, stem, head, gap)) return false;
                } else if (!WrappedEighthTriplets.matchesBeamRails(
                        ink.pixels, ink.w, ink.h, previousAxis, axis, gap, 2)) return false;
            }
            if (slot % 2 == 0) {
                if (WrappedTripletDotInk.singleAugmentationCore(
                        ink.pixels, ink.w, ink.h, head.x, head.y, row.get(slot + 1).x, gap) == null) return false;
            } else if (!provesBackwardDottedHook(ink, head, row.get(slot - 1), gap)
                    && !compactDownstemHook(ink, previousStem, stem, gap, -1)) return false;
            previousAxis = axis;
            previousStem = stem;
        }
        return true;
    }

    // The gap may cross a staff stripe. Require bare paper between both groups off the ruled rows.
    private static boolean staffOnlyQuartetGap(Ink ink, PairedTremoloInk.Stem left,
            PairedTremoloInk.Stem right, Head owner, float gap) {
        int distance = right.x() - left.x(), inset = Math.round(.34f * gap);
        int mid = (left.x() + right.x()) / 2;
        var rules = localRules(ink, mid, owner.y + owner.note.staffStep() * gap * .5f, gap);
        if (rules == null || distance < 1.65f * gap) return false;
        int top = Math.min(left.endY(), right.endY()) - Math.round(1.65f * gap);
        int bottom = Math.max(left.endY(), right.endY()) + Math.round(.17f * gap);
        int clear = 0;
        for (int x = left.x() + inset; x <= right.x() - inset; x++) {
            boolean empty = true;
            for (int y = top; y <= bottom; y++) {
                if (!ink.dark(x, y)) continue;
                if (!(onRule(rules, y, .18f)
                        && ink.dark(x - Math.round(.42f * gap), y)
                        && ink.dark(x + Math.round(.42f * gap), y))) {
                    empty = false;
                    break;
                }
            }
            clear = empty ? clear + 1 : 0;
            if (clear >= .27f * gap) return true;
        }
        return false;
    }


    static void includeDottedPairTerminalDigits(List<PrintedFingeringDigits.Mark> marks,
            ScorePageInterpretation score, ScoreMeterMap meter, byte[] raw, byte[] normalized, int w, int h) {
        if (raw == null || normalized == null || raw.length != (long) w * h || raw.length != normalized.length)
            return;
        var original = new Ink(raw, w, h);
        var prepared = new Ink(normalized, w, h);
        var bars = groupedHeads(score, w, h);
        var occupied = new java.util.HashSet<Integer>();
        for (var mark : marks)
            if (mark.placement() == PrintedFingeringDigits.Placement.BELOW) occupied.add(mark.noteIndex());
        for (int i = 0; i < bars.size(); i++) {
            float beats = meter.beatsInMeasure(i);
            final int barIndex = i;
            if ((beats != 3 && beats != 4) || bars.get(i).size() != 4 * beats
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == barIndex)) continue;
            var region = score.measures().get(i);
            var order = bars.get(i).stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var terminal = order.get(order.size() - 1);
            boolean downstems = order.stream().allMatch(head -> head.note.stemDirection() == -1);
            if (terminal.note.stemDirection() != -1
                    || repairVerifiedDottedPairs(original, prepared, region, order, true, downstems) == null) continue;
            var owners = order.stream().map(Head::index).toList();
            PrintedFingeringDigits.includeClosedDottedPairBeamDigits(marks, occupied, score, owners,
                    raw, normalized, w, h);
            if (downstems) {
                float gap = (region.bottom() - region.top()) * h / 8.2f;
                float floor = terminal.y + terminal.note.staffStep() * gap * .5f;
                float edge = region.right() * w - gap * .72f;
                var a = localRules(original, edge, floor, gap);
                var b = localRules(prepared, edge, floor, gap);
                if (a != null && b != null)
                    PrintedFingeringDigits.includeClosedPairStaffThrees(
                            marks, occupied, score, owners, raw, normalized, w, h, a, b);
            }
            if (downstems && beats == 4) {
                var additions = new ArrayList<PrintedFingeringDigits.Mark>();
                for (int end = 3; end + 1 < order.size(); end += 4) {
                    var owner = order.get(end);
                    if (occupied.contains(owner.index)) continue;
                    int digit = readDottedPairTerminal(score, original, prepared, region,
                            order.subList(0, end + 1), null, marks);
                    if (digit > 0) additions.add(new PrintedFingeringDigits.Mark(
                            owner.index, digit, PrintedFingeringDigits.Placement.BELOW));
                }
                marks.addAll(additions);
            }
            if (occupied.contains(terminal.index)) continue;
            int digit = readDottedPairTerminal(score, original, prepared, region, order, null, marks);
            if (digit > 0)
                marks.add(new PrintedFingeringDigits.Mark(
                        terminal.index, digit, PrintedFingeringDigits.Placement.BELOW));
        }
    }

    static void includeDottedPairFloorDigits(List<PrintedFingeringDigits.Mark> marks,
            ScorePageInterpretation score, ScoreMeterMap meter, byte[] raw, byte[] normalized, int width, int height) {
        if (marks.size() < 3 || raw == null || normalized == null || raw.length != (long) width * height
                || raw.length != normalized.length) return;
        var original = new Ink(raw, width, height);
        var prepared = new Ink(normalized, width, height);
        var known = List.copyOf(marks);
        var below = new java.util.HashMap<Integer, Integer>();
        for (var mark : known) {
            if (mark.placement() != PrintedFingeringDigits.Placement.BELOW) continue;
            below.put(mark.noteIndex(), mark.digit());
        }
        var rows = groupedHeads(score, width, height);
        for (int i = 0; i < rows.size(); i++) {
            float span = meter.beatsInMeasure(i);
            final int measure = i;
            var row = rows.get(i);
            long anchored = row.stream().filter(h -> below.containsKey(h.index)).count();
            if ((span != 3 && span != 4) || row.size() != 4 * span || anchored < 3 || anchored == row.size()
                    || row.stream().anyMatch(h -> h.note.stemDirection() != -1)
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var proof = repairVerifiedDottedPairs(original, prepared, score.measures().get(i), ordered, true, true);
            if (proof == null || !proof.equals(ordered.stream().map(Head::note).toList())) continue;
            PrintedFingeringDigits.includeProvedStaffDigitRow(marks, score,
                    ordered.stream().map(Head::index).toList(), below, raw, normalized, width, height);
        }
    }

    private static int readDottedPairTerminal(ScorePageInterpretation score, Ink raw, Ink prepared,
            MeasureRegion region, List<Head> row, Head alias, List<PrintedFingeringDigits.Mark> marks) {
        var terminal = row.get(row.size() - 1);
        float gap = (region.bottom() - region.top()) * raw.h / 8.2f;
        float floor = terminal.y + terminal.note.staffStep() * gap * .5f;
        float edge = region.right() * raw.w - gap * .72f;
        var tracks = new ArrayList<StaffPitchTrack>(2);
        for (var source : List.of(raw, prepared)) {
            var rules = localRules(source, edge, floor, gap);
            if (rules == null) return -1;
            if (alias != null && !PrintedBeamShafts.at(source.pixels, source.w, source.h,
                    alias.x, alias.y, rules[1], new PrintedBeamShafts.Budget(20_000)).isEmpty()) return -1;
            tracks.add(StaffPitchTrack.fromVerifiedSamples(List.of(
                    new float[] {terminal.x - gap, rules[0], rules[1]},
                    new float[] {terminal.x + gap, rules[0], rules[1]})));
        }
        return PrintedFingeringDigits.readClosedDottedPairDigit(score, terminal.index,
                alias == null ? -1 : alias.x, alias == null ? -1 : alias.y,
                marks, raw.pixels, prepared.pixels, tracks.get(0), tracks.get(1), raw.w, raw.h);
    }

    /** The existing pairs supply the clock. Each changed dot or hook still needs its own ink. */
    private static List<ScoreNoteEvent> repairVerifiedDottedPairs(
            Ink raw, Ink normalized, MeasureRegion bar, List<Head> row, boolean verifyTerminalPair) {
        return repairVerifiedDottedPairs(raw, normalized, bar, row, verifyTerminalPair, false);
    }

    private static List<ScoreNoteEvent> repairVerifiedDottedPairs(
            Ink raw, Ink normalized, MeasureRegion bar, List<Head> row, boolean verifyTerminalPair,
            boolean verifiedRibbon) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        float left = bar.left() * raw.w, right = bar.right() * raw.w;
        if (gap < 10 || gap > 27 || bar.cadenceQuarterBeats() != 0
                || row.get(0).x - left > (row.size() == 12 ? 5 : 2.7f) * gap
                || right - row.get(row.size() - 1).x > 2.7f * gap) return null;
        var next = new ArrayList<ScoreNoteEvent>(row.size());
        int fixes = 0, exact = 0, clef = row.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < row.size(); i++) {
            var h = row.get(i);
            var n = h.note;
            boolean dotted = i % 2 == 0;
            if (!regular(n, 2) || n.clefBottomDiatonic() != clef || n.stemDirection() == 0
                    || n.beamCount() < (verifiedRibbon && !dotted ? 1 : 2) || dotted && n.beamCount() != 2
                    || !dotted && (n.stemDirection() != row.get(i - 1).note.stemDirection()
                            || n.augmentationDots() > 1)
                    || i > 0 && (h.x - row.get(i - 1).x < 1.45f * gap
                            || h.x - row.get(i - 1).x > 4.5f * gap)) return null;
            boolean match = n.beamCount() == (dotted ? 2 : 3) && n.augmentationDots() == (dotted ? 1 : 0);
            if (match) exact++;
            else fixes++;
            next.add(match ? n : rhythm(n, dotted));
        }
        if ((!verifyTerminalPair && fixes == 0) || exact < row.size() * .60f) return null;
        var terminal = row.get(row.size() - 1);
        float floor = terminal.y + terminal.note.staffStep() * gap * .5f;
        for (var source : List.of(raw, normalized)) {
            var rules = localRules(source, right - gap * .72f, floor, gap);
            if (rules == null || !barline(source, right, rules)) return null;
            for (int i = 0; i < row.size(); i++) {
                var head = row.get(i);
                if (head.note == next.get(i) && (!verifyTerminalPair || i < row.size() - 2)) continue;
                float nextX = i + 1 < row.size() ? row.get(i + 1).x : right;
                if (i % 2 == 0) {
                    if (WrappedTripletDotInk.singleAugmentationCore(
                            source.pixels, source.w, source.h, head.x, head.y, nextX, gap) == null)
                        return null;
                    continue;
                }
                var lead = row.get(i - 1);
                if (head.note.augmentationDots() != 0 && WrappedTripletDotInk.hasIndependentCore(
                        source.pixels, source.w, source.h, head.x, head.y, nextX, gap)) return null;
                if (head.note.beamCount() == 1) {
                    var a = PairedTremoloInk.stem(source.pixels, source.w, source.h, lead.x, lead.y, gap, -1);
                    var b = PairedTremoloInk.stem(source.pixels, source.w, source.h, head.x, head.y, gap, -1);
                    if (a == null || b == null || b.x() <= a.x()) return null;
                    float slope = (b.endY() - a.endY()) / (float) (b.x() - a.x());
                    if (Math.abs(slope) > .30f || !supportsPairRail(source, a, b, slope, gap, .20f, .45f)
                            || !supportsPairRail(source, a, b, slope, gap, .94f, 1.34f)) return null;
                }
                if (!provesBackwardDottedHook(source, head, lead, gap)) {
                    if (!verifiedRibbon) return null;
                    var a = PairedTremoloInk.stem(source.pixels, source.w, source.h, lead.x, lead.y, gap, -1);
                    var b = PairedTremoloInk.stem(source.pixels, source.w, source.h, head.x, head.y, gap, -1);
                    if (a == null || b == null || !compactDownstemHook(source, a, b, gap, -1)) return null;
                }
            }
        }
        float meter = row.size() / 4f;
        for (int i = 0; i < next.size(); i++) {
            var placement = ScoreNoteTiming.writtenPlacement(next.get(i), next, meter).orElse(null);
            if (placement == null || placement.onsetBeats() != i / 2 * .5f + (i % 2 == 0 ? 0 : .375f)
                    || placement.durationBeats() != (i % 2 == 0 ? .375f : .125f)) return null;
        }
        return next;
    }

    private static boolean provesBackwardDottedHook(Ink ink, Head head, Head lead, float gap) {
        var owner = new OwnedTertiaryBeamlets.Head(head.index, head.note, head.x, head.y, gap);
        var peer = new OwnedTertiaryBeamlets.Head(lead.index, lead.note, lead.x, lead.y, gap);
        return OwnedTertiaryBeamlets.provesMonophonicBackwardHook(ink.pixels, ink.w, ink.h, owner, peer, gap)
                || (head.note.stemDirection() == -1 && OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                        ink.pixels, ink.w, ink.h, head.x, head.y, lead.x, lead.y, gap))
                || OwnedTertiaryBeamlets.provesBackwardHookFromStemTrace(ink.pixels, ink.w, ink.h, owner, peer, gap)
                || thickUpstemHook(ink, head, lead, gap);
    }

    /** A heavy third hook can touch the inner rail, but ends before the clear double rail. */
    private static boolean thickUpstemHook(Ink ink, Head head, Head lead, float gap) {
        if (head.note.stemDirection() != 1) return false;
        var b = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, head.x, head.y, gap, 1);
        var a = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, lead.x, lead.y, gap, 1);
        if (a == null || b == null || b.x() - a.x() < 2.15f * gap) return false;
        float slope = (b.endY() - a.endY()) / (float) (b.x() - a.x());
        if (Math.abs(slope) > .27f) return false;
        int exposed = 0;
        for (int d = Math.round(1.23f * gap); d <= Math.round(1.68f * gap); d++) {
            int y = Math.round(b.endY() - slope * d), x = b.x() - d;
            if (ink.weight(x, y + Math.round(.28f * gap), 1) == 3
                    && ink.weight(x, y + Math.round(1.04f * gap), 1) == 3
                    && ink.weight(x, y + Math.round(.73f * gap), 0) == 0) exposed++;
        }
        if (exposed < .23f * gap) return false;
        for (int depth = Math.round(1.72f * gap); depth <= Math.round(2.08f * gap); depth++) {
            int attached = 0, near = Math.round(.72f * gap);
            for (int d = 1; d <= near; d++)
                if (ink.weight(b.x() - d, Math.round(b.endY() - slope * d) + depth, 1) == 3) attached++;
            int far = Math.round(1.25f * gap);
            if (attached >= near - 1
                    && ink.weight(b.x() - far, Math.round(b.endY() - slope * far) + depth, 1) == 0)
                return true;
        }
        return false;
    }

    /** Recovers one misread short note in four fully owned dotted pairs before a half note. */
    private static Head dottedPairsBeforeHalf(Ink raw, Ink corrected, MeasureRegion region, List<Head> row) {
        float gap = (region.bottom() - region.top()) * raw.h / 8.2f;
        if (region.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23) return null;

        var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var last = ordered.get(8);
        var direction = ordered.get(0).note.stemDirection();
        if (direction == 0) return null;
        var clef = ordered.get(0).note.clefBottomDiatonic();
        Head wrong = null;
        for (int slot = 0; slot < ordered.size(); slot++) {
            var head = ordered.get(slot);
            var note = head.note;
            if (!regularMarkings(note, 1) || note.stemDirection() != direction
                    || note.clefBottomDiatonic() != clef) return null;
            if (slot > 0) {
                float space = head.x - ordered.get(slot - 1).x;
                if (space < gap * 1.3f || space > gap * 4.3f) return null;
            }
            if (slot == 8) {
                if (note.unbeamedDurationBeats() != 2 || note.beamCount() != 0
                        || note.augmentationDots() != 0) return null;
            } else if (note.unbeamedDurationBeats() != 0) {
                return null;
            } else if (slot % 2 == 0) {
                if (note.beamCount() != 2 || note.augmentationDots() != 1) return null;
            } else if (note.beamCount() == 2 && note.augmentationDots() == 1 && wrong == null) {
                wrong = head;
            } else if (note.beamCount() != 3 || note.augmentationDots() != 0) {
                return null;
            }
        }
        if (wrong == null) return null;

        float right = region.right() * raw.w;
        float floor = last.y + last.note.staffStep() * gap * .5f;
        for (var plane : List.of(raw, corrected)) {
            var rules = localRules(plane, right - gap * .72f, floor, gap);
            if (rules == null || !barline(plane, right, rules) || !hollowDyadHead(plane, last, rules[1]))
                return null;
            for (int slot = 0; slot < 8; slot += 2) {
                var dotted = ordered.get(slot);
                var shortNote = ordered.get(slot + 1);
                if (!dot(plane, dotted, shortNote.x, rules[1])
                        || dot(plane, shortNote, ordered.get(slot + 2).x, rules[1])
                        || !OwnedTertiaryBeamlets.provesMonophonicBackwardHook(
                                plane.pixels, plane.w, plane.h,
                                new OwnedTertiaryBeamlets.Head(
                                        shortNote.index, shortNote.note, shortNote.x, shortNote.y, rules[1]),
                                new OwnedTertiaryBeamlets.Head(
                                        dotted.index, dotted.note, dotted.x, dotted.y, rules[1]),
                                rules[1])) return null;
            }
        }
        return wrong;
    }

    /** Prove four dotted pairs, their beam fragments, and the independently barred half note. */
    static boolean provesDottedPairsWithBeamAliases(
            List<ScoreNoteEvent> notes, List<Integer> members, MeasureRegion region,
            byte[] raw, byte[] normalized, int width, int height) {
        var row = members.stream().map(index -> {
            var note = notes.get(index);
            float x = (region.left() + note.positionInMeasure() * (region.right() - region.left())) * width;
            return new Head(index, note, x, note.pageY() * height);
        }).toList();
        return provesDottedPairsWithBeamAliases(new Ink(raw, width, height),
                new Ink(normalized, width, height), region, row);
    }

    private static boolean provesDottedPairsWithBeamAliases(
            Ink raw, Ink normalized, MeasureRegion region, List<Head> row) {
        float gap = (region.bottom() - region.top()) * raw.h / 8.2f;
        if (region.cadenceQuarterBeats() != 0 || gap < 10 || gap > 23) return false;

        var ordered = row.stream().filter(h -> h.note.stemDirection() != 0)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        if (ordered.size() != 9) return false;
        var aliases = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        var last = ordered.get(8);
        var direction = ordered.get(0).note.stemDirection();
        if (direction != -1 || aliases.size() != 2) return false;
        var clef = ordered.get(0).note.clefBottomDiatonic();
        for (int slot = 0; slot < ordered.size(); slot++) {
            var head = ordered.get(slot);
            var note = head.note;
            if (!regularMarkings(note, 1) || note.stemDirection() != direction
                    || note.clefBottomDiatonic() != clef) return false;
            if (slot > 0) {
                float space = head.x - ordered.get(slot - 1).x;
                if (space < gap * 1.3f || space > gap * 4.3f) return false;
            }
            if (slot == 8) {
                if (note.unbeamedDurationBeats() != 2 || note.beamCount() != 0
                        || note.augmentationDots() != 0) return false;
            } else if (note.unbeamedDurationBeats() != 0) {
                return false;
            } else if (slot % 2 == 0) {
                if (note.beamCount() != 2 || note.augmentationDots() != 1) return false;
            } else if (note.beamCount() < 2 || note.beamCount() > 3 || note.augmentationDots() != 0) {
                return false;
            }
        }
        float right = region.right() * raw.w;
        float floor = last.y + last.note.staffStep() * gap * .5f;
        for (var plane : List.of(raw, normalized)) {
            var rules = localRules(plane, right - gap * .72f, floor, gap);
            if (rules == null || !barline(plane, right, rules) || !hollowDyadHead(plane, last, rules[1]))
                return false;
            PairedTremoloInk.Stem previous = null;
            for (int slot = 0; slot < 8; slot++) {
                var owner = ordered.get(slot);
                var stem = PairedTremoloInk.stem(plane.pixels, plane.w, plane.h, owner.x, owner.y, gap, -1);
                if (stem == null) return false;
                if (previous != null) {
                    if (stem.x() <= previous.x()) return false;
                    float slope = (stem.endY() - previous.endY()) / (float) (stem.x() - previous.x());
                    boolean connected = slot != 4;
                    if (Math.abs(slope) > .30f
                            || supportsPairRail(plane, previous, stem, slope, gap, .20f, .45f) != connected
                            || supportsPairRail(plane, previous, stem, slope, gap, .94f, 1.34f) != connected)
                        return false;
                }
                previous = stem;
            }
            for (var alias : aliases)
                if (!cadenceBeamOwnsAlias(plane, ordered, alias, gap, clef)) return false;
            for (int slot = 0; slot < 8; slot += 2) {
                var dotted = ordered.get(slot);
                var shortNote = ordered.get(slot + 1);
                if (!dot(plane, dotted, shortNote.x, rules[1])
                        || dot(plane, shortNote, ordered.get(slot + 2).x, rules[1])) return false;
                boolean hook = provesBackwardDottedHook(plane, shortNote, dotted, gap);
                if (!hook && !OwnedTertiaryBeamlets.provesMonophonicBackwardHook(
                                plane.pixels, plane.w, plane.h,
                                new OwnedTertiaryBeamlets.Head(
                                        shortNote.index, shortNote.note, shortNote.x, shortNote.y, rules[1]),
                                new OwnedTertiaryBeamlets.Head(
                                        dotted.index, dotted.note, dotted.x, dotted.y, rules[1]),
                                rules[1])) return false;
            }
        }
        return true;
    }

    /** A stemless mask inside the two shared rails belongs to the printed beam. */
    private static boolean cadenceBeamOwnsAlias(Ink ink, List<Head> owners, Head alias, float gap, int clef) {
        var note = alias.note;
        if (!regularMarkings(note, 0) || note.clefBottomDiatonic() != clef || note.articulations() != 0
                || note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
            return false;
        for (int slot = 1; slot < 8; slot++) {
            var left = owners.get(slot - 1);
            var right = owners.get(slot);
            if (alias.x <= left.x || alias.x >= right.x) continue;
            var a = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, left.x, left.y, gap, -1);
            var b = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, right.x, right.y, gap, -1);
            return a != null && b != null && b.x() > a.x()
                    && ribbonInkOwnsAlias(ink, alias, left, right, a, b, gap);
        }
        return false;
    }

    /** The first and final short notes may carry hooks on opposite sides of the staff. */
    private static boolean provesOpposedDottedHookEnds(
            Ink corrected, Ink raw, MeasureRegion region, List<Head> row, float gap) {
        int terminal = row.size() - 1;
        int clef = row.get(0).note.clefBottomDiatonic();
        for (int slot = 0; slot < row.size(); slot++) {
            Head head = row.get(slot);
            boolean dotted = slot % 2 == 0;
            int expectedBeams = dotted || slot == 1 || slot == terminal ? 2 : 3;
            if (!regular(head.note)
                    || head.note.stemDirection() != (slot < row.size() / 2 ? -1 : 1)
                    || head.note.clefBottomDiatonic() != clef
                    || head.note.beamCount() != expectedBeams
                    || head.note.augmentationDots() != (dotted ? 1 : 0)) return false;
            if (slot > 0) {
                float advance = head.x - row.get(slot - 1).x;
                if (advance < gap * 1.55f || advance > gap * 3.7f) return false;
            }
        }
        float right = region.right() * raw.w;
        var last = row.get(terminal);
        float floor = last.y + last.note.staffStep() * gap * .5f;
        for (Ink plane : List.of(raw, corrected)) {
            float[] rules = localRules(plane, right - gap * .72f, floor, gap);
            if (rules == null || !barline(plane, right, rules)) return false;
            for (int slot : new int[] {1, terminal}) {
                var own = row.get(slot);
                var before = row.get(slot - 1);
                if (!OwnedTertiaryBeamlets.provesMonophonicBackwardHook(
                        plane.pixels,
                        plane.w,
                        plane.h,
                        new OwnedTertiaryBeamlets.Head(own.index, own.note, own.x, own.y, gap),
                        new OwnedTertiaryBeamlets.Head(
                                before.index, before.note, before.x, before.y, gap),
                        rules[1])) return false;
            }
        }
        return true;
    }

    private static boolean provesFourClosedDottedGroups(
            Ink ink, MeasureRegion region, List<Head> row, float gap) {
        int existingHooks = 0;
        int clef = row.get(0).note.clefBottomDiatonic();
        for (int i = 0; i < row.size(); i++) {
            var owner = row.get(i);
            boolean dotted = i % 2 == 0;
            if (!regular(owner.note)
                    || owner.note.stemDirection() != -1
                    || owner.note.clefBottomDiatonic() != clef
                    || owner.note.beamCount() < 2
                    || dotted && owner.note.beamCount() != 2
                    || i > 0
                            && (owner.x - row.get(i - 1).x < gap * 1.6f
                                    || owner.x - row.get(i - 1).x > gap * 4.2f)) return false;
            if (!dotted && owner.note.beamCount() == 3) existingHooks++;
        }
        if (existingHooks < 4) return false;

        float left = region.left() * ink.w, right = region.right() * ink.w;
        var track = readOctaveStaff(ink, left, right, region.bottom() * ink.h - 2.1f * gap, gap);
        if (track == null || !barline(ink, right, track.at(right))) return false;

        List<Shaft> priorShafts = List.of();
        for (int i = 0; i < row.size(); i++) {
            var owner = row.get(i);
            var currentShafts = shafts(ink, owner, gap);
            if (currentShafts.isEmpty()) return false;
            if (i % 2 == 0) {
                var core =
                        WrappedTripletDotInk.uniqueCore(
                                ink.pixels, ink.w, ink.h, owner.x, owner.y, row.get(i + 1).x, gap);
                if (core == null || core.x() - owner.x < gap * .95f) return false;
                if (i > 0
                        && gapBetween(
                                        ink,
                                        row.get(i - 1),
                                        owner,
                                        priorShafts,
                                        currentShafts,
                                        gap,
                                        track)
                                != (i % 4 == 0)) return false;
            } else {
                var before = row.get(i - 1);
                float next = i + 1 < row.size() ? row.get(i + 1).x : right;
                if (owner.note.augmentationDots() != 0 && dot(ink, owner, next, gap)) return false;
                if (!ownedLink(ink, priorShafts, currentShafts, i - 1, gap, track, null, true)) {
                    if (owner.note.beamCount() != 3) return false;
                    if (!OwnedTertiaryBeamlets.provesJoinedBackwardHook(
                                    ink.pixels,
                                    ink.w,
                                    ink.h,
                                    owner.x,
                                    owner.y,
                                    before.x,
                                    before.y,
                                    gap)
                            && !OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                                    ink.pixels,
                                    ink.w,
                                    ink.h,
                                    owner.x,
                                    owner.y,
                                    before.x,
                                    before.y,
                                    gap)) return false;
                }
            }
            priorShafts = currentShafts;
        }
        return true;
    }

    static ScorePageInterpretation applyClosedTailDyadHooks(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, int w, int h) {
        if (gray == null || gray.length != (long) w * h || w < 256 || h < 128) return score;
        var bars = groupedHeads(score, w, h);
        var ink = new Ink(gray, w, h);
        var meter = inherited;
        int nextMeter = 0;
        List<ScoreNoteEvent> revised = null;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            if (meter == null
                    || meter.numerator() != 4
                    || meter.denominator() != 4
                    || bars.get(i).size() < 8) continue;
            final int index = i;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)
                    || score.multiRestGroups().stream()
                            .anyMatch(
                                    g ->
                                            g.firstMeasureIndex() <= index
                                                    && g.firstMeasureIndex() + g.measureCount()
                                                            > index)) continue;
            var row = bars.get(i).stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var tail = closedTailHook(row, score.measures().get(i), ink);
            if (tail == null) continue;
            if (revised == null) revised = new ArrayList<>(score.notes());
            revised.set(tail.upper.index, rhythm(tail.upper.note, false));
            revised.set(tail.lower.index, rhythm(tail.lower.note, false));
        }
        return revised == null
                ? score
                : score.withNotes(
                        revised,
                        java.util.stream.IntStream.range(0, score.notes().size()).boxed().toList());
    }

    private static Dyad closedTailHook(List<Head> row, MeasureRegion bar, Ink ink) {
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        float right = bar.right() * ink.w;
        if (bar.cadenceQuarterBeats() != 0
                || gap < 10
                || gap > 27
                || row.stream()
                        .anyMatch(head -> !regular(head.note) || head.note.stemDirection() != -1))
            return null;
        int first = row.size() - 8;
        if (first > 0 && row.get(first).x - row.get(first - 1).x < gap * 1.45f) return null;
        var dyads = new ArrayList<Dyad>(4);
        for (int slot = 0; slot < 4; slot++) {
            var a = row.get(first + 2 * slot);
            var b = row.get(first + 2 * slot + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            int interval = upper.note.staffStep() - lower.note.staffStep();
            int beams = slot == 1 ? 3 : 2;
            boolean dotted = slot % 2 == 0;
            float next = slot < 3 ? row.get(first + 2 * slot + 2).x : right;
            if ((interval != 7 && interval != 9)
                    || b.x - a.x > gap * .23f
                    || Math.abs((lower.y - upper.y) / gap - interval * .5f) > .30f
                    || upper.note.clefBottomDiatonic() != lower.note.clefBottomDiatonic()
                    || upper.note.octaveShift() != lower.note.octaveShift()
                    || upper.note.beamCount() != beams
                    || lower.note.beamCount() != beams
                    || upper.note.augmentationDots() != (dotted ? 1 : 0)
                    || lower.note.augmentationDots() != (dotted ? 1 : 0)
                    || (slot < 3 && (next - b.x < gap * 1.45f || next - b.x > gap * 4.2f)))
                return null;
            if (dotted && !dot(ink, upper, next, gap) && !dot(ink, lower, next, gap)) return null;
            if (!dotted
                    && (!dotAgreement(ink, upper, next, gap, false)
                            || !dotAgreement(ink, lower, next, gap, false))) return null;
            dyads.add(new Dyad(upper, lower));
        }
        var terminal = dyads.get(3);
        var peer = dyads.get(2);
        if (right - terminal.lower.x < gap * .65f
                || right - terminal.lower.x > gap * 2.75f
                || !hasClosingRule(ink.pixels, ink.w, ink.h, bar)) return null;
        var budget = new PrintedBeamShafts.Budget(160_000);
        if (!joinedTailHook(ink, dyads.get(1), dyads.get(0), gap, budget)
                || !joinedTailHook(ink, terminal, peer, gap, budget)) return null;
        var before = row.stream().map(Head::note).toList();
        var trial = new ArrayList<>(before);
        trial.set(first + 6, rhythm(before.get(first + 6), false));
        trial.set(first + 7, rhythm(before.get(first + 7), false));
        return closesSameTailClock(before, trial, first) ? terminal : null;
    }

    private static boolean joinedTailHook(
            Ink ink, Dyad owner, Dyad peer, float gap, PrintedBeamShafts.Budget budget) {
        return OwnedTertiaryBeamlets.provesJoinedClosingDyadHook(
                ink.pixels,
                ink.w,
                ink.h,
                owner.lower.x,
                owner.lower.y,
                owner.upper.x,
                owner.upper.y,
                peer.upper.x,
                peer.upper.y,
                peer.lower.x,
                peer.lower.y,
                gap,
                budget);
    }

    private static boolean closesSameTailClock(
            List<ScoreNoteEvent> before, List<ScoreNoteEvent> trial, int first) {
        for (int i = 0; i < before.size(); i++) {
            var old = ScoreNoteTiming.writtenPlacement(before.get(i), before, 4f).orElse(null);
            var now = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4f).orElse(null);
            if (old == null || now == null) return false;
            if (i < first + 6) {
                if (!old.equals(now) || now.onsetBeats() + now.durationBeats() > 3.875)
                    return false;
            }
            if (i < first) continue;
            int slot = (i - first) / 2;
            boolean dotted = slot % 2 == 0;
            double start = 3 + slot / 2 * .5 + (dotted ? 0 : .375);
            double value = dotted ? .375 : .125;
            if (old.onsetBeats() != start
                    || now.onsetBeats() != start
                    || now.durationBeats() != value
                    || old.durationBeats() != (slot == 3 ? .25 : value)) return false;
        }
        return true;
    }

    private static Head missingSingleHook(List<Head> row, float gap) {
        Head found = null;
        for (int i = 0; i < row.size(); i++) {
            var h = row.get(i);
            boolean dotted = i % 2 == 0;
            if (!regular(h.note)
                    || h.note.stemDirection() != -1
                    || h.note.augmentationDots() != (dotted ? 1 : 0)
                    || i > 0
                            && (h.x - row.get(i - 1).x < gap * 1.6f
                                    || h.x - row.get(i - 1).x > gap * 3.7f)) return null;
            if (h.note.beamCount() == (dotted ? 2 : 3)) continue;
            if (dotted || h.note.beamCount() != 2 || found != null) return null;
            found = h;
        }
        return found;
    }

    static ScorePageInterpretation applyClosedOctaves(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, int w, int h) {
        return applyClosedOctaves(score, inherited, gray, w, h, false, -1);
    }

    static ScorePageInterpretation applyClosedOctaveFragments(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] gray, int w, int h) {
        return applyClosedOctaveFragments(score, inherited, gray, w, h, -1);
    }

    static ScorePageInterpretation applyClosedOctaveFragments(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] gray,
            int w,
            int h,
            int physicalPage) {
        return applyClosedOctaves(score, inherited, gray, w, h, true, physicalPage);
    }

    static ScorePageInterpretation applyReversedOctaveHook(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] labels,
            byte[] gray,
            int w,
            int h) {
        if (gray == null
                || labels == null
                || gray.length != (long) w * h
                || labels.length != gray.length
                || w < 256
                || h < 128) return score;
        var bars = groupedHeads(score, w, h);
        var ink = new Ink(gray, w, h);
        var meter = inherited;
        int nextMeter = 0;
        ScoreNoteEvent[] revised = null;
        boolean[] suppressed = null;
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            final int index = i;
            if (meter == null
                    || meter.numerator() != 4
                    || meter.denominator() != 4
                    || bars.get(i).size() != 33
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index))
                continue;
            var region = score.measures().get(i);
            var row = bars.get(i);
            var aliases = row.stream().filter(head -> head.note.stemDirection() == 1).toList();
            if (aliases.size() != 1) continue;
            var alias = aliases.get(0);
            var terminal = reversedOctaveHook(row, alias, region, labels, ink);
            if (terminal == null) continue;
            if (revised == null) {
                revised = new ScoreNoteEvent[score.notes().size()];
                suppressed = new boolean[revised.length];
            }
            suppressed[alias.index] = true;
            revised[terminal.upper.index] = rhythm(terminal.upper.note, false);
            revised[terminal.lower.index] = rhythm(terminal.lower.note, false);
        }
        if (revised == null) return score;
        var retained = new ArrayList<ScoreNoteEvent>();
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < revised.length; i++) {
            if (suppressed[i]) continue;
            origins.add(i);
            retained.add(revised[i] == null ? score.notes().get(i) : revised[i]);
        }
        return score.withNotes(retained, origins);
    }

    /** A stamped third hook on the final octave can be returned as an extra full-sized head. */
    static ScorePageInterpretation applyStaffSideTerminalHooks(ScorePageInterpretation score,
            ScoreMeterChange inherited, byte[] gray, byte[] raw, int width, int height, int physicalPage) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length || width < 256 || height < 128) return score;
        var notes = score.notes();
        var bars = groupedHeads(score, width, height);
        var regions = score.measures();
        var normalized = new Ink(gray, width, height);
        var original = new Ink(raw, width, height);
        var meter = inherited;
        int nextMeter = 0;
        var removed = new java.util.BitSet();
        var edited = new ArrayList<>(notes);
        var beams = score.beamSource();
        for (int i = 0; i < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            final int index = i;
            var row = bars.get(i);
            var bar = regions.get(i);
            if (row.size() != 33 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || bar.cadenceQuarterBeats() != 0 || score.rests().stream().anyMatch(r -> r.measureIndex() == index))
                continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 10 || gap > 25) continue;
            var aliases = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
            if (aliases.size() != 1) continue;
            var alias = aliases.get(0);
            var rival = alias.note;
            if (!regularMarkings(rival, 0) || rival.articulations() != 0
                    || rival.augmentationDots() != 0 || rival.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || !(rival.beamCount() == 1 && rival.unbeamedDurationBeats() == 0
                        || rival.beamCount() == 0 && rival.unbeamedDurationBeats() == 1)) continue;
            var columns = terminalOctaveColumns(row, alias, gap);
            if (columns.isEmpty()) continue;
            var last = columns.get(columns.size() - 1);
            var previous = columns.get(14);
            if (alias.x <= previous.lower.x + .3f * gap || alias.x >= last.lower.x
                    || last.upper.note.beamCount() != 2 || last.lower.note.beamCount() != 2
                    || last.upper.note.augmentationDots() != 0 || last.lower.note.augmentationDots() != 0
                    || !staffSideTerminalAlias(normalized, bar, alias, last, previous, gap)
                    || !staffSideTerminalAlias(original, bar, alias, last, previous, gap)) continue;
            removed.set(alias.index);
            edited.set(last.upper.index, rhythm(last.upper.note, false));
            edited.set(last.lower.index, rhythm(last.lower.note, false));
            var attacks = columns.stream().map(ClosedDottedSixteenths::beamAttack).toList();
            var directions = java.util.Collections.nCopies(columns.size(), 1);
            if (physicalPage >= 0 && dottedOctaveInk(normalized, bar, columns, gap)
                    && dottedOctaveInk(original, bar, columns, gap)) {
                var checked = ClosedOctaveBeamSource.captureDetachedDottedDyads(
                        beams, physicalPage, gray, raw, width, height, gap, attacks, directions);
                if (hasFourDottedOctaveGroups(checked, columns)) {
                    beams = checked;
                    for (int slot = 0; slot < columns.size(); slot++) {
                        var dyad = columns.get(slot);
                        edited.set(dyad.upper.index, rhythm(dyad.upper.note, slot % 2 == 0));
                        edited.set(dyad.lower.index, rhythm(dyad.lower.note, slot % 2 == 0));
                    }
                }
            }
        }
        if (removed.isEmpty()) return score;
        var retained = IntStream.range(0, notes.size()).filter(i -> !removed.get(i)).boxed().toList();
        return score.withBeamSource(beams).withNotes(retained.stream().map(edited::get).toList(), retained);
    }

    private static List<Dyad> terminalOctaveColumns(List<Head> heads, Head alias, float gap) {
        var sorted = heads.stream().filter(h -> h != alias)
                .sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(16);
        int wrongStaffSteps = 0;
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            if (Math.abs(a.x - b.x) > .24f * gap
                    || lower.y - upper.y < 3.18f * gap || lower.y - upper.y > 3.85f * gap
                    || upper.note.clefBottomDiatonic() != lower.note.clefBottomDiatonic()) return List.of();
            int staffSteps = upper.note.staffStep() - lower.note.staffStep();
            if (staffSteps != 7 && (Math.abs(staffSteps - 7) != 1 || i >= 28 || ++wrongStaffSteps > 1))
                return List.of();
            for (var head : List.of(upper, lower)) {
                var note = head.note;
                if (note.stemDirection() != -1 || !regularMarkings(note, 1)
                        || !(note.beamCount() >= 1 && note.beamCount() <= 3 && note.unbeamedDurationBeats() == 0
                            || note.beamCount() == 0 && note.unbeamedDurationBeats() == 1)) return List.of();
            }
            if (!pairs.isEmpty()) {
                float advance = lower.x - pairs.get(pairs.size() - 1).lower.x;
                if (advance < 1.45f * gap || advance > 4.15f * gap) return List.of();
            }
            pairs.add(new Dyad(upper, lower));
        }
        return pairs;
    }

    private static boolean staffSideTerminalAlias(Ink ink, MeasureRegion bar, Head rival,
            Dyad last, Dyad previous, float gap) {
        if (!hasClosingRule(ink.pixels, ink.w, ink.h, bar)
                || bar.right() * ink.w - last.lower.x < .55f * gap
                || bar.right() * ink.w - last.lower.x > 2.75f * gap) return false;
        if (OwnedTertiaryBeamlets.ownsReversedOctaveHook(
                ink.pixels, ink.w, ink.h, rival.x, rival.y, last.lower.x, last.lower.y, last.upper.x,
                last.upper.y, previous.lower.x, previous.lower.y, gap, new PrintedBeamShafts.Budget(190_000)))
            return true;
        return rival.note.beamCount() == 1
                && OwnedTertiaryBeamlets.ownsShaftSideOctaveHookAlias(ink.pixels, ink.w, ink.h, rival.x, rival.y,
                        last.lower.x, last.lower.y, last.upper.x, last.upper.y, previous.lower.x, previous.lower.y, gap);
    }

    private static boolean dottedOctaveInk(Ink ink, MeasureRegion bar, List<Dyad> columns, float gap) {
        for (int i = 0; i < columns.size(); i++) {
            var dyad = columns.get(i);
            float edge = i + 1 == columns.size() ? bar.right() * ink.w : columns.get(i + 1).upper.x;
            boolean above = WrappedTripletDotInk.uniqueCore(
                    ink.pixels, ink.w, ink.h, dyad.upper.x, dyad.upper.y, edge, gap) != null;
            boolean below = WrappedTripletDotInk.uniqueCore(
                    ink.pixels, ink.w, ink.h, dyad.lower.x, dyad.lower.y, edge, gap) != null;
            if ((above || below) != (i % 2 == 0)) return false;
        }
        return true;
    }

    private static boolean hasFourDottedOctaveGroups(ScoreBeamSource beams, List<Dyad> columns) {
        for (int start = 0; start < columns.size(); start += 4) {
            var groupColumns = new ArrayList<ScoreBeamSource.Column>(4);
            for (var dyad : columns.subList(start, start + 4)) {
                var upper = beams.noteOrigins().get(dyad.upper.index);
                var lower = beams.noteOrigins().get(dyad.lower.index);
                if (upper.isEmpty() || lower.isEmpty()) return false;
                groupColumns.add(new ScoreBeamSource.Column(List.of(upper.get(), lower.get())));
            }
            if (beams.groups().stream().noneMatch(g -> g.columns().equals(groupColumns)
                    && g.bridges().equals(List.of(3, 3, 3)) && g.hooks().size() == 2)) return false;
        }
        return true;
    }


    private static Dyad reversedOctaveHook(
            List<Head> row, Head alias, MeasureRegion bar, byte[] labels, Ink ink) {
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        float left = bar.left() * ink.w, right = bar.right() * ink.w;
        if (bar.cadenceQuarterBeats() != 0
                || gap < 10
                || gap > 27
                || right - left < gap * 32
                || right - left > gap * 49
                || !regular(alias.note, 0)
                || alias.note.beamCount() != 1
                || alias.note.articulations() != 0
                || alias.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                || !compactHookMask(labels, ink, alias, gap)) return null;
        var sorted =
                row.stream()
                        .filter(h -> h != alias)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        var dyads = new ArrayList<Dyad>(16);
        for (int j = 0; j < sorted.size(); j += 2) {
            var a = sorted.get(j);
            var b = sorted.get(j + 1);
            var top = a.y < b.y ? a : b;
            var low = top == a ? b : a;
            boolean dotted = j % 4 == 0;
            if (!octaveClockOwner(top.note, dotted, true)
                    || !octaveClockOwner(low.note, dotted, true)
                    || top.note.stemDirection() != -1
                    || low.note.stemDirection() != -1
                    || top.note.beamCount() < 2
                    || low.note.beamCount() < 2
                    || top.note.clefBottomDiatonic() != low.note.clefBottomDiatonic()
                    || top.note.octaveShift() != low.note.octaveShift()
                    || top.note.staffStep() - low.note.staffStep() != 7
                    || b.x - a.x > gap * .24f
                    || low.y - top.y < gap * 3.18f
                    || low.y - top.y > gap * 3.8f) return null;
            if (!dyads.isEmpty()) {
                float advance = low.x - dyads.get(dyads.size() - 1).lower.x;
                if (advance < gap * 1.45f || advance > gap * 4.15f) return null;
            }
            dyads.add(new Dyad(top, low));
        }
        var terminal = dyads.get(15);
        var peer = dyads.get(14);
        if (terminal.upper.note.beamCount() != 2
                || terminal.lower.note.beamCount() != 2
                || right - terminal.lower.x < gap * .7f
                || right - terminal.lower.x > gap * 2.75f
                || !hasClosingRule(ink.pixels, ink.w, ink.h, bar)
                || dot(ink, terminal.upper, right, gap)
                || dot(ink, terminal.lower, right, gap)
                || !dot(ink, peer.upper, terminal.upper.x, gap)
                        && !dot(ink, peer.lower, terminal.upper.x, gap)) return null;
        var budget = new PrintedBeamShafts.Budget(240_000);
        boolean owned = false;
        for (int j = 1; j < dyads.size(); j += 2) {
            var following = dyads.get(j);
            var preceding = dyads.get(j - 1);
            if (preceding.lower.x < alias.x
                    && alias.x < following.lower.x
                    && OwnedTertiaryBeamlets.ownsReversedOctaveHook(
                            ink.pixels,
                            ink.w,
                            ink.h,
                            alias.x,
                            alias.y,
                            following.lower.x,
                            following.lower.y,
                            following.upper.x,
                            following.upper.y,
                            preceding.lower.x,
                            preceding.lower.y,
                            gap,
                            budget)) owned = true;
        }
        if (!owned
                || commonShafts(ink, terminal, gap, budget).isEmpty()
                || commonShafts(ink, peer, gap, budget).isEmpty()
                || budget.refused()
                || !OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        terminal.lower.x,
                        terminal.lower.y,
                        peer.lower.x,
                        peer.lower.y,
                        gap)
                || !OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        terminal.upper.x,
                        terminal.upper.y,
                        peer.upper.x,
                        peer.upper.y,
                        gap)) return null;
        var trial = new ArrayList<>(sorted.stream().map(Head::note).toList());
        for (int j = 30; j < 32; j++) trial.set(j, rhythm(trial.get(j), false));
        return fillsDottedClock(trial, 4) ? terminal : null;
    }

    private static boolean compactHookMask(byte[] labels, Ink ink, Head owner, float gap) {
        int x = Math.round(owner.x), y = Math.round(owner.y), reach = Math.round(gap * .86f);
        if (x < reach
                || y < reach
                || x + reach >= ink.w
                || y + reach >= ink.h
                || labels[y * ink.w + x] != OmrMeasurePostProcessor.NOTEHEAD) return false;
        int side = reach * 2 + 1, firstX = x - reach, firstY = y - reach;
        int[] queue = new int[side * side];
        boolean[] seen = new boolean[queue.length];
        int center = reach * side + reach;
        queue[0] = center;
        seen[center] = true;
        int total = 1, dark = 0, xmin = x, xmax = x, ymin = y, ymax = y;
        long sumX = 0, sumY = 0;
        var neighbors = new int[] {-1, 1, -side, side};
        for (int cursor = 0; cursor < total; cursor++) {
            int k = queue[cursor], dx = k % side, dy = k / side;
            if (dx == 0 || dy == 0 || dx == side - 1 || dy == side - 1) return false;
            int px = firstX + dx, py = firstY + dy;
            xmin = Math.min(xmin, px);
            xmax = Math.max(xmax, px);
            ymin = Math.min(ymin, py);
            ymax = Math.max(ymax, py);
            sumX += px;
            sumY += py;
            if (ink.dark(px, py)) dark++;
            for (int offset : neighbors) {
                int n = k + offset;
                if (!seen[n]
                        && labels[(firstY + n / side) * ink.w + firstX + n % side]
                                == OmrMeasurePostProcessor.NOTEHEAD) {
                    seen[n] = true;
                    queue[total++] = n;
                }
            }
        }
        int span = xmax - xmin + 1, height = ymax - ymin + 1;
        return total >= gap * gap * .28f
                && total <= gap * gap * .50f
                && span >= gap * .74f
                && span <= gap * 1.05f
                && height >= gap * .45f
                && height <= gap * .81f
                && dark >= total * .95f
                && Math.abs(sumX / (float) total - owner.x) <= .25f
                && Math.abs(sumY / (float) total - owner.y) <= .25f;
    }

    private static ScorePageInterpretation applyClosedOctaves(
            ScorePageInterpretation score,
            ScoreMeterChange inherited,
            byte[] gray,
            int w,
            int h,
            boolean inspectFragments,
            int physicalPage) {
        if (gray == null || gray.length != (long) w * h || w < 256 || h < 128) return score;
        var bars = groupedHeads(score, w, h);
        var notes = score.notes();
        var revised = new ArrayList<>(notes);
        var suppressed = new boolean[notes.size()];
        var ink = new Ink(gray, w, h);
        var regions = score.measures();
        var meter = inherited;
        var beams = score.beamSource();
        int nextMeter = 0;
        for (int i = 1; i + 1 < bars.size(); i++) {
            while (nextMeter < score.meterChanges().size()
                    && score.meterChanges().get(nextMeter).measureIndex() <= i)
                meter = score.meterChanges().get(nextMeter++);
            var row = bars.get(i);
            if (inspectFragments
                    && meter != null
                    && meter.numerator() == 3
                    && meter.denominator() == 4
                    && row.size() == 25
                    && closedTripleOctaves(ink, score, regions, i, row, revised, suppressed))
                continue;
            if (meter == null
                    || meter.numerator() != 4
                    || meter.denominator() != 4
                    || row.size() != (inspectFragments ? 33 : 32)
                    || regions.get(i).cadenceQuarterBeats() != 0) continue;
            final int bar = i;
            if (score.rests().stream().anyMatch(r -> r.measureIndex() == bar)
                    || score.multiRestGroups().stream()
                            .anyMatch(
                                    g ->
                                            g.firstMeasureIndex() <= bar
                                                    && g.firstMeasureIndex() + g.measureCount()
                                                            > bar)) continue;
            Head fragment = null;
            List<Dyad> cadence = List.of();
            if (inspectFragments) {
                var suspects = row.stream().filter(head -> head.note.stemDirection() == 0).toList();
                if (suspects.size() != 1) continue;
                fragment = suspects.get(0);
                if (fragment.note.augmentationDots() != 0 || fragment.note.articulations() != 0)
                    continue;
                if (fragment.note.beamCount() == 0
                        && fragment.note.unbeamedDurationBeats() == 1
                        && regularMarkings(fragment.note, 1)
                        && score.rests().stream().noneMatch(r -> r.measureIndex() == bar - 1)) {
                    cadence = halfOctaveCadence(ink, regions.get(i - 1), bars.get(i - 1));
                    if (cadence.isEmpty()) continue;
                } else if (!regular(fragment.note) || fragment.note.beamCount() != 3) continue;
                var owners = new ArrayList<>(row);
                owners.remove(fragment);
                row = owners;
            }
            var dyads =
                    closedOctaves(
                            ink,
                            regions.get(i - 1),
                            regions.get(i),
                            regions.get(i + 1),
                            row,
                            fragment,
                            cadence.isEmpty() ? null : cadence.get(cadence.size() - 1));
            if (dyads.isEmpty()) continue;
            var trial = new ArrayList<ScoreNoteEvent>(32);
            for (int j = 0; j < dyads.size(); j++) {
                trial.add(rhythm(dyads.get(j).upper.note, j % 2 == 0));
                trial.add(rhythm(dyads.get(j).lower.note, j % 2 == 0));
            }
            if (!fillsDottedClock(trial, 4f)) continue;
            if (fragment != null) {
                suppressed[fragment.index] = true;
                if (physicalPage >= 0 && cadence.isEmpty()) {
                    var attacks = dyads.stream().map(ClosedDottedSixteenths::beamAttack).toList();
                    var barRegion = regions.get(i);
                    float gap = (barRegion.bottom() - barRegion.top()) * h / 8.2f;
                    beams =
                            ClosedOctaveBeamSource.capture(
                                    beams, physicalPage, gray, w, h, gap, attacks);
                }
            }
            for (int j = 0; j < dyads.size(); j++) {
                revised.set(dyads.get(j).upper.index, trial.get(j * 2));
                revised.set(dyads.get(j).lower.index, trial.get(j * 2 + 1));
            }
        }
        var retained = new ArrayList<ScoreNoteEvent>(notes.size());
        var owners = new ArrayList<Integer>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            if (suppressed[i]) continue;
            owners.add(i);
            retained.add(revised.get(i));
        }
        if (retained.equals(notes)) return score;
        var result =
                (beams == score.beamSource() ? score : score.withBeamSource(beams))
                        .withNotes(retained, owners);
        return inspectFragments
                ? result
                : result.withClockContextSource(score.clockContextSource());
    }

    private static boolean closedTripleOctaves(
            Ink ink,
            ScorePageInterpretation score,
            List<MeasureRegion> regions,
            int bar,
            List<Head> row,
            ArrayList<ScoreNoteEvent> revised,
            boolean[] suppressed) {
        if (regions.get(bar).cadenceQuarterBeats() != 0
                || score.rests().stream().anyMatch(r -> r.measureIndex() == bar)
                || score.multiRestGroups().stream()
                        .anyMatch(
                                g ->
                                        g.firstMeasureIndex() <= bar
                                                && g.firstMeasureIndex() + g.measureCount() > bar))
            return false;
        var fragments = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
        if (fragments.size() != 1) return false;
        var fragment = fragments.get(0);
        if (!regular(fragment.note)
                || fragment.note.beamCount() != 1
                || fragment.note.augmentationDots() != 0
                || fragment.note.articulations() != 0) return false;

        var region = regions.get(bar);
        var prior = regions.get(bar - 1);
        var following = regions.get(bar + 1);
        float left = region.left() * ink.w, right = region.right() * ink.w;
        float gap = (region.bottom() - region.top()) * ink.h / 8.2f;
        if (gap < 10
                || gap > 27
                || right - left < 28 * gap
                || right - left > 38 * gap
                || Math.abs(prior.top() - region.top()) * ink.h > gap
                || Math.abs(following.top() - region.top()) * ink.h > gap
                || left - prior.right() * ink.w < .35f * gap
                || left - prior.right() * ink.w > 1.65f * gap
                || following.left() * ink.w - right < .35f * gap
                || following.left() * ink.w - right > 1.65f * gap) return false;

        var sorted =
                row.stream()
                        .filter(h -> h != fragment)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        var track = readOctaveStaff(ink, left, right, region.bottom() * ink.h - 2.1f * gap, gap);
        float first = sorted.get(0).x, last = sorted.get(23).x;
        if (track == null
                || first - left < .88f * gap
                || first - left > 2.4f * gap
                || !singleStaffStroke(
                        ink, track, prior.right() * ink.w, left, Float.NEGATIVE_INFINITY, gap)
                || !closingStroke(ink, track, right, following.left() * ink.w, last, gap))
            return false;

        var dyads = new ArrayList<Dyad>(12);
        var trial = new ArrayList<ScoreNoteEvent>(24);
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            boolean dotted = i % 4 == 0;
            float next = i + 2 < sorted.size() ? sorted.get(i + 2).x : right;
            if (!regular(upper.note)
                    || !regular(lower.note)
                    || upper.note.stemDirection() != -1
                    || lower.note.stemDirection() != -1
                    || upper.note.beamCount() < 2
                    || lower.note.beamCount() < 2
                    || dotted
                            && (upper.note.beamCount() != 2
                                    || lower.note.beamCount() != 2
                                    || upper.note.augmentationDots() != 1
                                    || lower.note.augmentationDots() != 1)
                    || upper.note.clefBottomDiatonic() != sorted.get(0).note.clefBottomDiatonic()
                    || lower.note.clefBottomDiatonic() != upper.note.clefBottomDiatonic()
                    || upper.note.staffStep() - lower.note.staffStep() != 7
                    || upper.note.octaveShift() != lower.note.octaveShift()
                    || b.x - a.x > .24f * gap
                    || lower.y - upper.y < 3.18f * gap
                    || lower.y - upper.y > 3.8f * gap
                    || !threeBeatDots(ink, upper, lower, next, gap, dotted)) return false;
            dyads.add(new Dyad(upper, lower));
            trial.add(rhythm(upper.note, dotted));
            trial.add(rhythm(lower.note, dotted));
        }
        if (!fillsDottedClock(trial, 3f)) return false;
        var attacks = dyads.stream().map(ClosedDottedSixteenths::beamAttack).toList();
        if (!ClosedOctaveBeamSource.provesTripleClock(
                ink.pixels,
                ink.w,
                ink.h,
                gap,
                attacks,
                new ClosedOctaveBeamSource.Fragment(fragment.x, fragment.y))) return false;
        suppressed[fragment.index] = true;
        for (int i = 0; i < dyads.size(); i++) {
            var dyad = dyads.get(i);
            revised.set(dyad.upper.index, trial.get(2 * i));
            revised.set(dyad.lower.index, trial.get(2 * i + 1));
        }
        return true;
    }

    private static boolean threeBeatDots(
            Ink ink, Head upper, Head lower, float next, float gap, boolean dotted) {
        var above =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels, ink.w, ink.h, upper.x, upper.y, next, gap);
        var below =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels, ink.w, ink.h, lower.x, lower.y, next, gap);
        return (above != null) == dotted && (below != null) == dotted;
    }

    private static ClosedOctaveBeamSource.Attack beamAttack(Dyad dyad) {
        return new ClosedOctaveBeamSource.Attack(
                dyad.upper.index,
                dyad.lower.index,
                dyad.upper.x,
                dyad.upper.y,
                dyad.lower.x,
                dyad.lower.y);
    }

    private static ArrayList<List<Head>> groupedHeads(ScorePageInterpretation score, int w, int h) {
        var notes = score.notes();
        var bars = new ArrayList<List<Head>>();
        for (var ignored : score.measures()) bars.add(new ArrayList<>());
        for (int i = 0; i < notes.size(); i++) {
            var n = notes.get(i);
            var bar = score.measures().get(n.measureIndex());
            bars.get(n.measureIndex())
                    .add(
                            new Head(
                                    i,
                                    n,
                                    (bar.left()
                                                    + n.positionInMeasure()
                                                            * (bar.right() - bar.left()))
                                            * w,
                                    n.pageY() * h));
        }
        return bars;
    }

    private record Dyad(Head upper, Head lower) {}

    /** Repairs dot ownership only where two rasters prove the full alternating dotted-octave bar. */
    static ScorePageInterpretation restoreDottedOctaveClock(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] original, int width, int height, int physicalPage) {
        if (physicalPage < 0 || gray == null || original == null
                || gray.length != (long) width * height || gray.length != original.length) return score;
        var bars = groupedHeads(score, width, height);
        List<ScoreNoteEvent> edited = null;
        var meter = inherited;
        int change = 0;
        for (int measure = 0; measure < bars.size(); measure++) {
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= measure)
                meter = score.meterChanges().get(change++);
            var row = bars.get(measure);
            var region = score.measures().get(measure);
            int index = measure;
            if (row.size() != 32 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || region.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == index)
                    || row.stream().noneMatch(h -> h.note.augmentationDots() == 2
                            || h.note.beamCount() == 3 && h.note.augmentationDots() == 1)) continue;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (gap < 12 || gap > 24) continue;
            var columns = dottedClockColumns(row, gap, false);
            if (columns.isEmpty() || !dottedClockInk(gray, width, height, columns, region, gap)
                    || !dottedClockInk(original, width, height, columns, region, gap)) continue;
            float right = region.right() * width;
            if (!hasClosingRule(gray, width, height, region) || !hasClosingRule(original, width, height, region))
                continue;
            var proof = ClosedOctaveBeamSource.captureDetachedDottedDyads(
                    ScoreBeamSource.empty(score.notes().size()), physicalPage, gray, original, width, height, gap,
                    columns.stream().map(ClosedDottedSixteenths::beamAttack).toList(),
                    columns.stream().map(dyad -> -dyad.upper.note.stemDirection()).toList());
            if (right - columns.get(15).lower.x < gap * .55f || !hasFourDottedOctaveGroups(proof, columns))
                continue;
            if (edited == null) edited = new ArrayList<>(score.notes());
            for (int i = 0; i < columns.size(); i++) {
                var pair = columns.get(i);
                boolean dotted = (i & 1) == 0;
                edited.set(pair.upper.index, rhythm(pair.upper.note, dotted));
                edited.set(pair.lower.index, rhythm(pair.lower.note, dotted));
            }
        }
        if (edited == null) return score;
        return score.withNotes(edited, IntStream.range(0, edited.size()).boxed().toList());
    }

    static ScorePageInterpretation restoreClosedDottedSixthHooks(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] original, int width, int height, int physicalPage) {
        if (gray == null || original == null || gray.length != (long) width * height
                || gray.length != original.length) return score;
        var bars = groupedHeads(score, width, height);
        var ink = new Ink(gray, width, height);
        var raw = new Ink(original, width, height);
        var beams = score.beamSource();
        List<ScoreNoteEvent> edited = null;
        var meter = inherited;
        int change = 0;
        for (int index = 0; index < bars.size(); index++) {
            int measure = index;
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= measure)
                meter = score.meterChanges().get(change++);
            var row = bars.get(measure);
            var bar = score.measures().get(measure);
            if (row.size() != 32 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == measure)) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 24) continue;
            var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var pairs = new ArrayList<Dyad>(16);
            var missing = new java.util.BitSet(16);
            for (int slot = 0; slot < 16; slot++) {
                var first = ordered.get(2 * slot);
                var second = ordered.get(2 * slot + 1);
                var pair = first.y < second.y ? new Dyad(first, second) : new Dyad(second, first);
                var top = pair.upper.note;
                var lower = pair.lower.note;
                boolean dotted = (slot & 1) == 0;
                if (second.x - first.x > .24f * gap
                        || Math.abs(pair.lower.y - pair.upper.y - 2.5f * gap) > .35f * gap
                        || top.staffStep() - lower.staffStep() != 5
                        || top.clefBottomDiatonic() != lower.clefBottomDiatonic()
                        || !regularMarkings(top, 1) || !regularMarkings(lower, 1)
                        || !isPrintedSixthClockHead(top, dotted) || !isPrintedSixthClockHead(lower, dotted)
                        || top.beamCount() != lower.beamCount()) break;
                if (slot > 0) {
                    float spacing = pair.upper.x - pairs.get(slot - 1).upper.x;
                    if (spacing < 1.5f * gap || spacing > 3.4f * gap) break;
                }
                pairs.add(pair);
                if (!dotted && top.beamCount() == 2) missing.set(slot);
            }
            if (pairs.size() != 16 || missing.cardinality() > 3) continue;
            if (!missing.isEmpty() && (!provesDottedSixthHooks(ink, bar, pairs, missing, gap)
                    || !provesDottedSixthHooks(raw, bar, pairs, missing, gap))) continue;
            if (physicalPage >= 0 && provesSixthBeamGroups(ink, bar, pairs, gap)
                    && provesSixthBeamGroups(raw, bar, pairs, gap)) {
                beams = ClosedOctaveBeamSource.projectDottedRibbons(beams, physicalPage,
                        pairs.stream().map(ClosedDottedSixteenths::beamAttack).toList());
            }
            if (missing.isEmpty()) continue;
            if (edited == null) edited = new ArrayList<>(score.notes());
            for (int slot = missing.nextSetBit(0); slot >= 0; slot = missing.nextSetBit(slot + 1)) {
                var pair = pairs.get(slot);
                edited.set(pair.upper.index, rhythm(pair.upper.note, false));
                edited.set(pair.lower.index, rhythm(pair.lower.note, false));
            }
        }
        if (beams != score.beamSource()) score = score.withBeamSource(beams);
        if (edited == null) return score;
        return score.withNotes(edited, IntStream.range(0, edited.size()).boxed().toList());
    }

    /** Recover a missing high head only when the full closed dyad clock and both beam fragments agree. */
    static ScorePageInterpretation restoreDottedSixthLedgerOpening(
            ScorePageInterpretation score, ScoreMeterChange inherited, byte[] labels,
            byte[] gray, byte[] original, int width, int height) {
        if (labels == null || gray == null || original == null || width <= 0 || height <= 0
                || gray.length != (long) width * height || gray.length != original.length
                || labels.length != gray.length) return score;
        var rows = groupedHeads(score, width, height);
        var rawInk = new Ink(original, width, height);
        var ink = new Ink(gray, width, height);
        var meter = inherited;
        int change = 0;
        var additions = new java.util.HashMap<Integer, ScoreNoteEvent>();
        var removed = new java.util.BitSet(score.notes().size());
        var repaired = new java.util.BitSet(score.notes().size());
        for (int measure = 0; measure < rows.size(); measure++) {
            int index = measure;
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= index)
                meter = score.meterChanges().get(change++);
            var row = rows.get(index);
            var bar = score.measures().get(index);
            if (row.size() != 33 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var owners = row.stream().filter(n -> n.note.stemDirection() == -1)
                    .sorted(Comparator.comparingDouble(Head::x)).toList();
            if (owners.size() != 31) continue;
            var aliases = row.stream().filter(n -> n.note.stemDirection() != -1).toList();
            if (aliases.stream().anyMatch(a -> !ordinarySixthBeamFragment(a.note)
                    || score.beamSource().noteOrigins().get(a.index).isPresent())) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 24) continue;
            var lower = owners.get(0);
            if (lower.x - bar.left() * width < .65f * gap || lower.x - bar.left() * width > 1.6f * gap
                    || lower.note.staffStep() < 10 || lower.note.octaveShift() != 0
                    || !regularMarkings(lower.note, 1) || !isPrintedSixthClockHead(lower.note, true)) continue;
            var upper = printedOpeningSixth(labels, ink, rawInk, bar, lower, gap);
            if (upper == null) continue;
            var pairs = new ArrayList<Dyad>(16);
            pairs.add(new Dyad(upper, lower));
            var missing = new java.util.BitSet(16);
            for (int slot = 1; slot < 16; slot++) {
                var first = owners.get(2 * slot - 1);
                var second = owners.get(2 * slot);
                var pair = first.y < second.y ? new Dyad(first, second) : new Dyad(second, first);
                var top = pair.upper.note;
                var bottom = pair.lower.note;
                boolean dotted = (slot & 1) == 0;
                float spacing = pair.upper.x - pairs.get(slot - 1).upper.x;
                if (second.x - first.x > .24f * gap || spacing < 1.5f * gap || spacing > 3.4f * gap
                        || Math.abs(pair.lower.y - pair.upper.y - 2.5f * gap) > .35f * gap
                        || top.staffStep() - bottom.staffStep() != 5
                        || top.clefBottomDiatonic() != lower.note.clefBottomDiatonic()
                        || bottom.clefBottomDiatonic() != lower.note.clefBottomDiatonic()
                        || top.octaveShift() != 0 || bottom.octaveShift() != 0
                        || !regularMarkings(top, 1) || !regularMarkings(bottom, 1)
                        || !isPrintedSixthClockHead(top, dotted) || !isPrintedSixthClockHead(bottom, dotted)
                        || top.beamCount() != bottom.beamCount()) break;
                pairs.add(pair);
                if (!dotted && top.beamCount() == 2) missing.set(slot);
            }
            if (pairs.size() != 16 || missing.isEmpty() || missing.cardinality() > 3
                    || !provesDottedSixthHooks(ink, bar, pairs, missing, gap, aliases)
                    || !provesDottedSixthHooks(rawInk, bar, pairs, missing, gap, aliases)) continue;
            additions.put(lower.index, upper.note);
            for (var a : aliases) removed.set(a.index);
            for (int slot = missing.nextSetBit(0); slot >= 0; slot = missing.nextSetBit(slot + 1)) {
                var pair = pairs.get(slot);
                repaired.set(pair.upper.index);
                repaired.set(pair.lower.index);
            }
        }
        if (additions.isEmpty()) return score;
        var edited = new ArrayList<ScoreNoteEvent>(score.notes().size());
        var origins = new ArrayList<Integer>(score.notes().size());
        for (int i = 0; i < score.notes().size(); i++) {
            if (removed.get(i)) continue;
            var added = additions.get(i);
            if (added != null) {
                edited.add(added);
                origins.add(-1);
            }
            var old = score.notes().get(i);
            edited.add(repaired.get(i) ? rhythm(old, false) : old);
            origins.add(i);
        }
        return score.withNotes(edited, origins);
    }

    private static boolean ordinarySixthBeamFragment(ScoreNoteEvent n) {
        return regularMarkings(n, 1) && (n.articulations() & ~NoteArticulation.STACCATO) == 0
                && n.octaveShift() == 0 && n.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                && (n.stemDirection() == 1 && n.unbeamedDurationBeats() == 1
                        && n.beamCount() == 0 && n.augmentationDots() == 0
                    || n.stemDirection() == 0 && n.unbeamedDurationBeats() == 0
                        && n.beamCount() == 2 && n.augmentationDots() == 1);
    }

    private static Head printedOpeningSixth(byte[] labels, Ink ink, Ink raw, MeasureRegion bar,
            Head lower, float gap) {
        int step = lower.note.staffStep() + 5;
        float estimatedY = lower.y - 2.5f * gap;
        int left = Math.round(lower.x - .96f * gap), right = Math.round(lower.x + .96f * gap);
        int top = Math.round(estimatedY - .86f * gap), bottom = Math.round(estimatedY + .86f * gap);
        if (left < 0 || right >= ink.w || top < 0 || bottom >= ink.h) return null;
        int loX = right, hiX = left, loY = bottom, hiY = top;
        int area = 0, dark = 0, sumX = 0, sumY = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (labels[y * ink.w + x] != OmrMeasurePostProcessor.NOTEHEAD) continue;
                area++;
                if (ink.dark(x, y) && raw.dark(x, y)) dark++;
                sumX += x;
                sumY += y;
                loX = Math.min(loX, x);
                hiX = Math.max(hiX, x);
                loY = Math.min(loY, y);
                hiY = Math.max(hiY, y);
            }
        }
        int span = hiX - loX + 1, depth = hiY - loY + 1;
        if (loX <= left || hiX >= right || loY <= top || hiY >= bottom
                || span < 1.16f * gap || span > 1.68f * gap || depth < gap || depth > 1.38f * gap
                || area < span * depth * .65f || dark < area * .92f) return null;
        float x = (float) sumX / area, y = (float) sumY / area;
        if (Math.abs(lower.x - x) > .20f * gap || Math.abs(estimatedY - y) > .26f * gap
                || !hasOpeningLedgerPhase(ink, bar, x, y, step, gap)
                || !hasOpeningLedgerPhase(raw, bar, x, y, step, gap)) return null;
        int outer = Math.max(Math.round(bar.left() * ink.w - .65f * gap), 0);
        for (int row = top; row <= bottom; row++) {
            for (int col = outer; col < loX; col++) {
                if (labels[row * ink.w + col] == OmrMeasurePostProcessor.CLEF_OR_KEY) return null;
            }
        }
        float position = (x / ink.w - bar.left()) / (bar.right() - bar.left());
        var recovered = new ScoreNoteEvent(lower.note.measureIndex(), position, step, 0, 1,
                y / ink.h, false, 1, 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, 0, 1, 0, 0,
                lower.note.clefBottomDiatonic()).withStemDirection(-1);
        return new Head(-1, recovered, x, y);
    }

    private static boolean hasOpeningLedgerPhase(
            Ink ink, MeasureRegion bar, float x, float y, int step, float gap) {
        var track = WrappedMeasureBoundaries.verifiedStaffPockets(ink.pixels, ink.w, ink.h, bar, true);
        if (track == null) return false;
        var staff = track.at(x);
        if (Math.abs(y - (staff[0] - step * staff[1] * .5f)) > .22f * gap) return false;
        int count = 0;
        int half = Math.round(.70f * gap);
        for (int offset : new int[]{2, 4}) {
            int row = Math.round(staff[0] - (step - offset) * staff[1] * .5f);
            for (int shift = -2; shift <= 2; shift++) {
                int dark = 0;
                int middle = Math.round(x);
                for (int col = middle - half; col <= middle + half; col++)
                    if (ink.dark(col, row + shift)) dark++;
                if (dark >= (2 * half + 1) * .92f) {
                    count++;
                    break;
                }
            }
        }
        return count == 2;
    }

    private static boolean isPrintedSixthClockHead(ScoreNoteEvent note, boolean dotted) {
        return note.stemDirection() == -1 && note.unbeamedDurationBeats() == 0
                && note.augmentationDots() == (dotted ? 1 : 0)
                && (note.beamCount() == 2 || (!dotted && note.beamCount() == 3));
    }

    private static boolean provesDottedSixthHooks(
            Ink ink, MeasureRegion bar, List<Dyad> pairs, java.util.BitSet missing, float gap) {
        return provesDottedSixthHooks(ink, bar, pairs, missing, gap, List.of());
    }

    private static boolean provesDottedSixthHooks(Ink ink, MeasureRegion bar, List<Dyad> pairs,
            java.util.BitSet missing, float gap, List<Head> aliases) {
        boolean ledgerRun = !aliases.isEmpty();
        var axes = dottedSixthAxes(ink, bar, pairs, gap, ledgerRun);
        if (axes.isEmpty()) return false;
        int radius = Math.round(.19f * gap);
        for (int i = 1; i < axes.size(); i++) {
            var left = axes.get(i - 1);
            var right = axes.get(i);
            int center = (left.x + right.x) / 2;
            int tip = Math.round((left.tip + right.tip) / 2f);
            int outerRail = ink.weight(center, tip - Math.round(.25f * gap), radius);
            int innerRail = ink.weight(center, tip - Math.round(1.04f * gap), radius);
            if (i % 4 == 0) {
                if (outerRail > radius || innerRail > radius) return false;
            } else if (outerRail < 2 * radius - 1 || innerRail < 2 * radius - 1) return false;
            if (missing.get(i) && !provesBackwardSixthHook(ink, left, right, gap, radius, ledgerRun))
                return false;
        }
        return aliases.stream().allMatch(alias -> liesInsideMergedRibbon(ink, axes, alias, gap));
    }

    private static List<Shaft> dottedSixthAxes(
            Ink ink, MeasureRegion bar, List<Dyad> pairs, float gap, boolean ledgerRun) {
        var staff = WrappedMeasureBoundaries.verifiedStaffPockets(ink.pixels, ink.w, ink.h, bar, true);
        if (staff == null || !WrappedMeasureBoundaries.confirmsClosedWithVerifiedStaff(
                ink.pixels, ink.w, ink.h, bar, pairs.get(0).upper.x, pairs.get(15).upper.x, staff)) return List.of();
        var axes = new ArrayList<Shaft>(pairs.size());
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            float edge = i == pairs.size() - 1 ? bar.right() * ink.w : pairs.get(i + 1).upper.x;
            boolean dotted = (i & 1) == 0;
            for (var head : List.of(pair.upper, pair.lower)) {
                var dot = WrappedTripletDotInk.uniqueCore(ink.pixels, ink.w, ink.h, head.x, head.y, edge, gap);
                if ((dot != null) != dotted) return List.of();
            }
            var axis = sharedSixthShaft(ink, pair, gap, ledgerRun);
            if (axis == null) return List.of();
            axes.add(axis);
        }
        return axes;
    }

    /** Confirm ribbon edges and the direction of every level-three hook before assigning beam owners. */
    private static boolean provesSixthBeamGroups(Ink ink, MeasureRegion bar, List<Dyad> pairs, float gap) {
        boolean ledgerRun = pairs.get(0).upper.note.staffStep() >= 16;
        var axes = dottedSixthAxes(ink, bar, pairs, gap, ledgerRun);
        if (axes.isEmpty()) return false;
        int radius = Math.round(.19f * gap);
        for (int i = 1; i < axes.size(); i++) {
            var left = axes.get(i - 1);
            var right = axes.get(i);
            var cap = new Beam(left, right, 0, 0);
            boolean joins = i % 4 != 0;
            for (float fraction : new float[]{.27f, .5f, .73f}) {
                int x = Math.round(left.x + (right.x - left.x) * fraction);
                float tip = cap.outer(x);
                int outer = ink.weight(x, Math.round(tip - .25f * gap), radius);
                int inner = ink.weight(x, Math.round(tip - 1.04f * gap), radius);
                if (joins ? outer < 2 * radius - 1 || inner < 2 * radius - 1
                        : outer > radius || inner > radius) return false;
                if ((!joins || (i & 1) == 0)
                        && ink.weight(x, Math.round(tip - 1.66f * gap), radius) > radius) return false;
            }
            if ((i & 1) != 0 && !hasExclusiveBackwardSixthHook(ink, cap, left, right, gap, radius))
                return false;
        }
        return true;
    }

    private static boolean hasExclusiveBackwardSixthHook(
            Ink ink, Beam cap, Shaft preceding, Shaft owner, float gap, int radius) {
        int inner = Math.round(.19f * gap);
        int outer = Math.round(.69f * gap);
        int far = Math.round(1.17f * gap);
        int limit = Math.round(1.30f * gap);
        if (owner.x - limit <= preceding.x) return false;
        for (int depth = Math.round(1.45f * gap); depth <= Math.round(1.90f * gap); depth++) {
            boolean owned = true;
            for (int offset = inner; offset <= outer && owned; offset++) {
                int before = owner.x - offset, after = owner.x + offset;
                owned = ink.weight(before, Math.round(cap.outer(before) - depth), radius) >= 2 * radius - 1
                        && ink.weight(after, Math.round(cap.outer(after) - depth), radius) <= radius;
            }
            for (int offset = far; offset <= limit && owned; offset++) {
                int before = owner.x - offset;
                owned = ink.weight(before, Math.round(cap.outer(before) - depth), radius) <= radius;
            }
            if (owned) return true;
        }
        return false;
    }

    private static boolean liesInsideMergedRibbon(Ink ink, List<Shaft> axes, Head alias, float gap) {
        for (int i = 1; i < axes.size(); i++) {
            if (i % 4 == 0) continue;
            var left = axes.get(i - 1);
            var right = axes.get(i);
            if (alias.x - left.x < .55f * gap || right.x - alias.x < .55f * gap) continue;
            // A merged ribbon stays thick across a much wider span than a printed head.
            var baseline = new Beam(left, right, 0, 0);
            float inward = baseline.outer(alias.x) - alias.y;
            if (inward < .30f * gap || inward > 1.10f * gap) continue;
            int radius = Math.round(.20f * gap);
            int halfSpan = Math.round(.96f * gap);
            int hits = 0;
            for (int dx = -halfSpan; dx <= halfSpan; dx++) {
                int x = Math.round(alias.x) + dx;
                int y = Math.round(baseline.outer(x) - inward);
                if (ink.weight(x, y, radius) >= 2 * radius) hits++;
            }
            if (hits >= (2 * halfSpan + 1) * .92f) return true;
        }
        return false;
    }

    private static Shaft sharedSixthShaft(Ink ink, Dyad pair, float gap, boolean ledgerRun) {
        int top = Math.round(pair.upper.y + .24f * gap);
        int bottom = Math.round(pair.lower.y + .45f * gap);
        int left = (int) Math.ceil(pair.lower.x - .78f * gap);
        int right = (int) Math.floor(pair.lower.x - .36f * gap);
        int limit = Math.round(pair.lower.y + (ledgerRun ? 5.15f : 4.6f) * gap);
        Shaft prior = null;
        Shaft best = null;
        for (int x = left; x <= right; x++) {
            int covered = 0;
            for (int y = top; y <= bottom; y++) if (ink.dark(x, y)) covered++;
            if (covered < (ledgerRun ? .82f : .90f) * (bottom - top + 1) || !ink.dark(x, bottom)) {
                prior = null;
                continue;
            }
            int end = bottom;
            while (end < limit && ink.dark(x, end + 1)) end++;
            var current = new Shaft(x, covered >= .90f * (bottom - top + 1)
                    && end < limit && end >= pair.lower.y + 2.8f * gap ? end : 0);
            if (prior != null) {
                var deep = current.tip >= prior.tip ? current : prior;
                if (deep.tip > 0 && (best == null || deep.tip >= best.tip)) best = deep;
            }
            prior = current;
        }
        return best;
    }

    private static boolean provesBackwardSixthHook(Ink ink, Shaft left, Shaft right, float gap, int radius, boolean ledgerRun) {
        var rail = new Beam(left, right, Math.round(1.64f * gap), 0);
        int inner = Math.round(.17f * gap);
        int outer = Math.round(.70f * gap);
        for (int offset = inner; offset <= outer; offset++) {
            int x = right.x - offset;
            if (ink.weight(x, Math.round(rail.outer(x)), radius) < 2 * radius - 1) return false;
        }
        for (int offset = Math.round((ledgerRun ? 1.17f : 1.08f) * gap); offset <= Math.round(1.30f * gap); offset++) {
            int x = right.x - offset;
            if (x <= left.x || ink.weight(x, Math.round(rail.outer(x)), radius) > radius) return false;
        }
        return true;
    }

    static ScorePageInterpretation restoreStaffRuleHookOctaves(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] original, int width, int height, int physicalPage) {
        if (physicalPage < 0 || gray == null || original == null
                || gray.length != (long) width * height || gray.length != original.length) return score;
        var bars = groupedHeads(score, width, height);
        var edited = new ArrayList<>(score.notes());
        var removed = new java.util.BitSet(edited.size());
        var beams = score.beamSource();
        var meter = inherited;
        int change = 0;
        for (int i = 0; i < bars.size(); i++) {
            while (change < score.meterChanges().size() && score.meterChanges().get(change).measureIndex() <= i)
                meter = score.meterChanges().get(change++);
            var row = bars.get(i);
            var bar = score.measures().get(i);
            int measure = i;
            if (row.size() != 33 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var aliases = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
            if (aliases.size() != 1) continue;
            var alias = aliases.get(0);
            if (!regularMarkings(alias.note, 0) || alias.note.beamCount() != 0
                    || alias.note.unbeamedDurationBeats() != 1 || alias.note.articulations() != 0
                    || alias.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY) continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            var pairs = dottedClockColumns(row.stream().filter(h -> h != alias).toList(), gap, true);
            if (pairs.isEmpty() || !dottedClockInk(gray, width, height, pairs, bar, gap)
                    || !dottedClockInk(original, width, height, pairs, bar, gap)
                    || !hasClosingRule(gray, width, height, bar) || !hasClosingRule(original, width, height, bar))
                continue;
            var attacks = pairs.stream().map(ClosedDottedSixteenths::beamAttack).toList();
            int matched = -1;
            for (int start = 4; start < pairs.size() - 4; start += 4) {
                var end = pairs.get(start + 3);
                if (end.lower.x - alias.x < .82f * gap || end.lower.x - alias.x > 1.10f * gap
                        || alias.note.octaveShift() != end.lower.note.octaveShift()) continue;
                matched = start;
                break;
            }
            if (matched < 0) continue;
            var proof = ClosedOctaveBeamSource.captureStaffRuleHookOctaves(
                    ScoreBeamSource.empty(edited.size()), physicalPage, gray, original,
                    width, height, gap, attacks, matched, alias.x, alias.y);
            if (!hasFourDottedOctaveGroups(proof, pairs)) continue;
            var projected = ClosedOctaveBeamSource.captureStaffRuleHookOctaves(beams, physicalPage,
                    gray, original, width, height, gap, attacks, matched, alias.x, alias.y);
            if (!hasFourDottedOctaveGroups(projected, pairs)) continue;
            beams = projected;
            removed.set(alias.index);
            for (int slot = 0; slot < pairs.size(); slot++) {
                var pair = pairs.get(slot);
                boolean dotted = (slot & 1) == 0;
                edited.set(pair.upper.index, rhythm(pair.upper.note, dotted));
                edited.set(pair.lower.index, rhythm(pair.lower.note, dotted));
            }
        }
        if (removed.isEmpty()) return score;
        var retained = IntStream.range(0, edited.size()).filter(i -> !removed.get(i)).boxed().toList();
        return score.withBeamSource(beams).withNotes(retained.stream().map(edited::get).toList(), retained);
    }


    /** Recover the four printed octave ribbons when dense side ink produces a stemless attack. */
    static ScorePageInterpretation restoreSideInkDottedOctaves(ScorePageInterpretation score,
            ScoreMeterChange inherited, byte[] labels, byte[] normalized, byte[] raw,
            int width, int height, int physicalPage, java.util.function.Consumer<SourceLocalAccidental.ProvedMark> marks) {
        if (physicalPage < 0 || normalized == null || raw == null
                || normalized.length != (long) width * height || raw.length != normalized.length) return score;
        var rows = groupedHeads(score, width, height);
        var edited = new ArrayList<>(score.notes());
        var removed = new java.util.BitSet(edited.size());
        var beams = score.beamSource();
        var meter = inherited;
        int change = 0;
        for (int m = 0; m < rows.size(); m++) {
            while (change < score.meterChanges().size() && score.meterChanges().get(change).measureIndex() <= m)
                meter = score.meterChanges().get(change++);
            var row = rows.get(m);
            var bar = score.measures().get(m);
            int measure = m;
            if ((row.size() != 33 && row.size() != 34)
                    || meter != null && (meter.numerator() != 4 || meter.denominator() != 4)
                    || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            var rivals = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
            if (rivals.size() != 1) continue;
            var alias = rivals.get(0);
            if (!regularMarkings(alias.note, 0) || alias.note.beamCount() != 3
                    || alias.note.unbeamedDurationBeats() != 0 || alias.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 24) continue;
            Head companion = row.size() == 34 ? sideInkCompanion(row, alias, gap) : null;
            if (row.size() == 34 && companion == null) continue;
            var pairs = dottedClockColumns(row.stream().filter(h -> h != alias && h != companion).toList(),
                    gap, false, 16, true);
            if (pairs.isEmpty() || pairs.stream().anyMatch(pair -> pair.upper.note.stemDirection() != -1)
                    || !hasClosingRule(normalized, width, height, bar) || !hasClosingRule(raw, width, height, bar)
                    || !dottedClockInk(normalized, width, height, pairs, bar, gap)
                    || !dottedClockInk(raw, width, height, pairs, bar, gap)) continue;
            int slot = borrowedOctaveSlot(normalized, raw, width, height, pairs, alias, companion, gap);
            if (slot < 0) continue;
            var attacks = pairs.stream().map(ClosedDottedSixteenths::beamAttack).toList();
            var anchored = anchorUnclaimedOctaves(beams, physicalPage, pairs);
            if (anchored == null) continue;
            var projection = ClosedOctaveBeamSource.captureDetachedDottedDyads(anchored, physicalPage,
                    normalized, raw, width, height, gap, attacks, java.util.Collections.nCopies(16, 1));
            if (projection == anchored || !hasFourDottedOctaveGroups(projection, pairs)) continue;
            beams = projection;
            removed.set(alias.index);
            if (companion != null) removed.set(companion.index);
            for (int i = 0; i < pairs.size(); i++) {
                var pair = pairs.get(i);
                edited.set(pair.upper.index, rhythm(pair.upper.note, (i & 1) == 0));
                edited.set(pair.lower.index, rhythm(pair.lower.note, (i & 1) == 0));
            }
            clearSideInkSharps(edited, labels, normalized, raw, width, height, pairs, slot, gap);
            captureOctaveSharps(edited, normalized, raw, width, bar, pairs, gap, marks);
        }
        if (removed.isEmpty()) return score;
        var retained = IntStream.range(0, edited.size()).filter(i -> !removed.get(i)).boxed().toList();
        return score.withBeamSource(beams).withNotes(retained.stream().map(edited::get).toList(), retained);
    }

    private static void captureOctaveSharps(List<ScoreNoteEvent> edited, byte[] normalized, byte[] raw,
            int width, MeasureRegion bar, List<Dyad> pairs, float gap,
            java.util.function.Consumer<SourceLocalAccidental.ProvedMark> marks) {
        float previous = bar.left() * width;
        for (var pair : pairs) {
            for (var head : List.of(pair.upper, pair.lower)) {
                var note = edited.get(head.index);
                if (note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_SHARP
                        && !AccidentalSlurSpines.prove(normalized, width, previous, head.x, head.y, gap,
                                ScoreNoteEvent.ACCIDENTAL_SHARP).isEmpty()
                        && !AccidentalSlurSpines.prove(raw, width, previous, head.x, head.y, gap,
                                ScoreNoteEvent.ACCIDENTAL_SHARP).isEmpty())
                    marks.accept(new SourceLocalAccidental.ProvedMark(note, ScoreNoteEvent.ACCIDENTAL_SHARP, false));
            }
            previous = pair.upper.x;
        }
    }

    private static ScoreBeamSource anchorUnclaimedOctaves(ScoreBeamSource source, int page, List<Dyad> pairs) {
        var origins = new ArrayList<>(source.noteOrigins());
        var occupied = new java.util.HashSet<ScoreBeamSource.Anchor>();
        for (var origin : origins) origin.ifPresent(occupied::add);
        for (var group : source.groups()) for (var column : group.columns()) occupied.addAll(column.members());
        for (var column : source.detachedColumns()) occupied.addAll(column.members());
        int next = ScoreBeamSource.MAX_NOTES - 1;
        for (var pair : pairs) {
            for (var head : List.of(pair.upper, pair.lower)) {
                if (origins.get(head.index).isPresent()) return null;
                while (next >= 0 && occupied.contains(new ScoreBeamSource.Anchor(page, next))) next--;
                if (next < 0) return null;
                var anchor = new ScoreBeamSource.Anchor(page, next--);
                occupied.add(anchor);
                origins.set(head.index, java.util.Optional.of(anchor));
            }
        }
        return new ScoreBeamSource(source.groups(), source.detachedColumns(), origins);
    }

    private static Head sideInkCompanion(List<Head> row, Head alias, float gap) {
        Head found = null;
        for (var h : row) {
            if (h.note.stemDirection() != -1 || h.note.staffStep() != alias.note.staffStep() + 7
                    || h.note.beamCount() != 2 || h.note.unbeamedDurationBeats() != 0
                    || h.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY || !regularMarkings(h.note, 0)
                    || Math.abs(h.x - alias.x) > .18f * gap || Math.abs(alias.y - h.y - 3.5f * gap) > .26f * gap)
                continue;
            if (found != null) return null;
            found = h;
        }
        return found;
    }

    private static int borrowedOctaveSlot(byte[] normalized, byte[] raw, int width, int height,
            List<Dyad> pairs, Head alias, Head companion, float gap) {
        for (int i = 1; i < pairs.size(); i += 2) {
            var previous = pairs.get(i - 1);
            var current = pairs.get(i);
            float advance = current.lower.x - alias.x;
            if (alias.x - previous.lower.x < 1.2f * gap || advance < .76f * gap || advance > 1.27f * gap
                    || Math.abs(alias.y - previous.lower.y) > .32f * gap
                    || alias.y < current.lower.y - 1.1f * gap || alias.y > current.lower.y - .55f * gap
                    || alias.note.staffStep() != previous.lower.note.staffStep()) continue;
            if (borrowsOctaveShaft(normalized, width, height, alias, companion, current, gap)
                    && borrowsOctaveShaft(raw, width, height, alias, companion, current, gap)) return i;
        }
        return -1;
    }

    private static boolean borrowsOctaveShaft(byte[] plane, int width, int height,
            Head alias, Head companion, Dyad owner, float gap) {
        var budget = new PrintedBeamShafts.Budget(100_000);
        var shafts = PrintedBeamShafts.at(plane, width, height, owner.lower.x, owner.lower.y, gap, budget);
        if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
        var real = shafts.get(0);
        var sides = PrintedBeamShafts.at(plane, width, height, alias.x, alias.y, gap, budget);
        if (budget.refused() || sides.size() != 2) return false;
        var stub = sides.stream().filter(s -> s.direction() > 0 && s.right() < real.left()
                    && real.endY() - s.endY() > 1.5f * gap).findFirst().orElse(null);
        if (stub == null) return false;
        if (companion != null) {
            var cap = PrintedBeamShafts.at(plane, width, height, companion.x, companion.y, gap, budget);
            if (budget.refused() || cap.size() != 1 || cap.get(0).direction() != 1
                    || cap.get(0).endY() > alias.y - .80f * gap
                    || Math.max(cap.get(0).left(), stub.left()) > Math.min(cap.get(0).right(), stub.right()))
                return false;
        }
        return sides.stream().anyMatch(s -> s.direction() < 0
                    && Math.max(s.left(), real.left()) <= Math.min(s.right(), real.right())
                    && Math.abs(s.endY() - owner.upper.y) < .32f * gap);
    }

    private static void clearSideInkSharps(List<ScoreNoteEvent> edited, byte[] labels, byte[] normalized,
            byte[] raw, int width, int height, List<Dyad> pairs, int slot, float gap) {
        var affected = pairs.get(slot);
        float previous = pairs.get(slot - 1).upper.x;
        for (var head : List.of(affected.upper, affected.lower)) {
            if (head.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP) return;
            for (var plane : List.of(normalized, raw)) {
                if (!AccidentalSlurSpines.prove(plane, width, previous, head.x, head.y, gap,
                            ScoreNoteEvent.ACCIDENTAL_SHARP).isEmpty()
                        || !AccidentalSlurSpines.proveInterruptedSharp(plane, width, previous, head.x, head.y, gap).isEmpty())
                    return;
            }
        }
        for (var head : List.of(affected.upper, affected.lower))
            edited.set(head.index, edited.get(head.index).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        for (int i = slot + 1; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            if (pair.upper.note.staffStep() != affected.upper.note.staffStep()) continue;
            if (pair.upper.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP
                    || pair.lower.note.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_SHARP) break;
            boolean unmarked = true;
            for (var head : List.of(pair.upper, pair.lower)) {
                unmarked &= PrintedAccidentalSpineRecovery.unmarkedRepeat(
                        labels, raw, normalized, width, height, pairs.get(i - 1).upper.x, head.x, head.y, gap);
            }
            if (!unmarked) break;
            for (var head : List.of(pair.upper, pair.lower))
                edited.set(head.index, edited.get(head.index).withWrittenAccidental(ScoreNoteEvent.ACCIDENTAL_FROM_KEY));
        }
    }

    /** Confirms a complete 3/4 bar of alternating dotted sixteenths and hooked thirty-seconds. */
    static ScorePageInterpretation restoreTripleDottedOctaveClock(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] original, int width, int height) {
        if (gray == null || original == null || gray.length != (long) width * height
                || original.length != gray.length) return score;
        var bars = groupedHeads(score, width, height);
        var normalized = new Ink(gray, width, height);
        var raw = new Ink(original, width, height);
        var meter = inherited;
        int change = 0;
        List<ScoreNoteEvent> edited = null;
        for (int i = 0; i < bars.size(); i++) {
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= i)
                meter = score.meterChanges().get(change++);
            var row = bars.get(i);
            var bar = score.measures().get(i);
            int measure = i;
            if (row.size() != 24 || meter == null || meter.numerator() != 3 || meter.denominator() != 4
                    || bar.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)
                    || row.stream().noneMatch(head -> head.note.augmentationDots() == 2
                            || head.note.beamCount() == 2 && head.note.augmentationDots() == 0))
                continue;
            float gap = (bar.bottom() - bar.top()) * height / 8.2f;
            if (gap < 12 || gap > 24
                    || !hasClosingRule(gray, width, height, bar)
                    || !hasClosingRule(original, width, height, bar)) continue;
            var octaves = dottedClockColumns(row, gap, false, 12);
            if (octaves.isEmpty()
                    || !provesTripleDottedOctaves(raw, bar, octaves, gap)
                    || !provesTripleDottedOctaves(normalized, bar, octaves, gap)) continue;
            if (edited == null) edited = new ArrayList<>(score.notes());
            for (int j = 0; j < octaves.size(); j++) {
                var octave = octaves.get(j);
                boolean dotted = j % 2 == 0;
                edited.set(octave.upper.index, rhythm(octave.upper.note, dotted));
                edited.set(octave.lower.index, rhythm(octave.lower.note, dotted));
            }
        }
        if (edited == null) return score;
        return score.withNotes(edited, IntStream.range(0, edited.size()).boxed().toList())
                .withClockContextSource(score.clockContextSource());
    }

    private static boolean provesTripleDottedOctaves(
            Ink ink, MeasureRegion bar, List<Dyad> octaves, float gap) {
        for (int i = 0; i < octaves.size(); i++) {
            var pair = octaves.get(i);
            boolean dotted = i % 2 == 0;
            float edge = i + 1 < octaves.size() ? octaves.get(i + 1).upper.x : bar.right() * ink.w;
            for (var head : List.of(pair.upper, pair.lower)) {
                var note = head.note;
                if (note.stemDirection() != -1 || note.beamCount() > 3
                        || note.staffIndex() != pair.upper.note.staffIndex()
                        || dotted && (note.beamCount() != 2 || note.augmentationDots() < 1)
                        || !dotted && note.augmentationDots() != 0) return false;
                var dot = WrappedTripletDotInk.uniqueCore(ink.pixels, ink.w, ink.h, head.x, head.y, edge, gap);
                if (dotted != (dot != null)) return false;
                if (note.augmentationDots() == 2
                        && WrappedTripletDotInk.uniqueCore(
                                ink.pixels, ink.w, ink.h, dot.x() - .54f * gap, head.y, edge, gap) != null)
                    return false;
            }
            if (!dotted && (i % 4 == 3 || pair.upper.note.beamCount() == 2 || pair.lower.note.beamCount() == 2)
                    && !concentratedDownwardHook(ink, pair, octaves.get(i - 1), gap)) return false;
        }
        return true;
    }


    private static List<Dyad> dottedClockColumns(List<Head> row, float gap, boolean allowLostHook) {
        return dottedClockColumns(row, gap, allowLostHook, 16);
    }

    private static List<Dyad> dottedClockColumns(List<Head> row, float gap, boolean allowLostHook, int count) {
        return dottedClockColumns(row, gap, allowLostHook, count, false);
    }

    private static List<Dyad> dottedClockColumns(List<Head> row, float gap, boolean allowLostHook, int count,
            boolean recoveredShortValues) {
        var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>(count);
        for (int i = 0; i < count; i++) {
            var a = ordered.get(i * 2);
            var b = ordered.get(i * 2 + 1);
            var top = a.y < b.y ? a : b;
            var lower = top == a ? b : a;
            boolean shortColumn = recoveredShortValues && i % 2 == 1;
            boolean rhythmFits = shortColumn
                    ? scannedShortOctaveHead(top.note) && scannedShortOctaveHead(lower.note)
                    : dottedClockHead(top.note, allowLostHook && i % 4 == 3 && lower.note.beamCount() == 3)
                            && dottedClockHead(lower.note, allowLostHook && i % 4 == 3 && top.note.beamCount() == 3);
            if (b.x - a.x > gap * .24f || Math.abs(lower.y - top.y - 3.5f * gap) > gap * .40f
                    || top.note.staffStep() - lower.note.staffStep() != 7
                    || top.note.clefBottomDiatonic() != lower.note.clefBottomDiatonic()
                    || !regularMarkings(top.note, 2) || !regularMarkings(lower.note, 2)
                    || top.note.stemDirection() == 0 || lower.note.stemDirection() != top.note.stemDirection()
                    || !rhythmFits)
                return List.of();
            if (i > 0 && (top.x - pairs.get(i - 1).upper.x < gap * 1.42f
                    || top.x - pairs.get(i - 1).upper.x > gap * 4.15f)) return List.of();
            pairs.add(new Dyad(top, lower));
        }
        return pairs;
    }

    private static boolean scannedShortOctaveHead(ScoreNoteEvent note) {
        return note.augmentationDots() == 0 && note.beamCount() <= 3
                && (note.beamCount() == 0 ? note.unbeamedDurationBeats() == 1 : note.unbeamedDurationBeats() == 0);
    }

    private static boolean dottedClockHead(ScoreNoteEvent note, boolean allowLostHook) {
        return note.beamCount() >= 2 && note.unbeamedDurationBeats() == 0
                || allowLostHook && note.beamCount() == 0 && note.unbeamedDurationBeats() == 1
                        && note.augmentationDots() == 0;
    }

    private static boolean dottedClockInk(byte[] plane, int width, int height,
            List<Dyad> columns, MeasureRegion bar, float gap) {
        int witnessed = 0;
        for (int i = 0; i < columns.size(); i++) {
            var pair = columns.get(i);
            float edge = i + 1 == columns.size() ? bar.right() * width : columns.get(i + 1).upper.x;
            var top = WrappedTripletDotInk.uniqueCore(plane, width, height, pair.upper.x, pair.upper.y, edge, gap);
            var lower = WrappedTripletDotInk.uniqueCore(plane, width, height, pair.lower.x, pair.lower.y, edge, gap);
            if ((i & 1) == 0) {
                if (top != null || lower != null) witnessed++;
                for (var head : List.of(pair.upper, pair.lower)) {
                    var dot = head == pair.upper ? top : lower;
                    if (head.note.augmentationDots() != 1 && dot == null) return false;
                    if (head.note.augmentationDots() == 2
                            && WrappedTripletDotInk.uniqueCore(plane, width, height,
                                    dot.x() - gap * .54f, head.y, edge, gap) != null) return false;
                }
            } else if (top != null || lower != null
                    || LegacyTripletDotInk.singleDotCore(plane, width, height, pair.upper.x, pair.upper.y, edge, gap)
                    || LegacyTripletDotInk.singleDotCore(plane, width, height, pair.lower.x, pair.lower.y, edge, gap)) {
                return false;
            }
        }
        return witnessed >= 7;
    }

    static ScorePageInterpretation restoreUnclaimedOctaveBeamGroups(
            ScorePageInterpretation score, ScoreMeterChange inherited,
            byte[] gray, byte[] original, int width, int height, int physicalPage) {
        if (physicalPage < 0 || gray == null || original == null || width < 1 || height < 1
                || gray.length != (long) width * height || gray.length != original.length)
            return score;
        var current = score.beamSource();
        var bars = groupedHeads(score, width, height);
        var ink = new Ink(gray, width, height);
        var meter = inherited;
        int change = 0;
        for (int bar = 0; bar < bars.size(); bar++) {
            while (change < score.meterChanges().size()
                    && score.meterChanges().get(change).measureIndex() <= bar)
                meter = score.meterChanges().get(change++);
            var row = bars.get(bar);
            var region = score.measures().get(bar);
            int measure = bar;
            if (row.size() != 32 || meter == null || meter.numerator() != 4 || meter.denominator() != 4
                    || region.cadenceQuarterBeats() != 0
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            float gap = (region.bottom() - region.top()) * height / 8.2f;
            if (gap < 10 || gap > 27) continue;
            float left = region.left() * width, right = region.right() * width;
            var track = readOctaveStaff(ink, left, right,
                    region.bottom() * height - 2.1f * gap, gap);
            if (track == null || !barline(ink, right, track.at(right))) continue;
            var notes = row.stream().map(Head::note).toList();
            var ordered = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
            var attacks = new ArrayList<ClosedOctaveBeamSource.Attack>(16);
            var directions = new ArrayList<Integer>(16);
            boolean octaves = Math.abs(ordered.get(0).y - ordered.get(1).y) >= gap * 2.8f;
            float distance = octaves ? 3.5f * gap : gap;
            for (int k = 0; k < 16; k++) {
                var a = ordered.get(2 * k);
                var b = ordered.get(2 * k + 1);
                if (b.x - a.x > gap * .30f
                        || Math.abs(Math.abs(a.y - b.y) - distance) > .55f * gap
                        || a.note.stemDirection() == 0 || a.note.stemDirection() != b.note.stemDirection()
                        || !octaves && a.note.stemDirection() != -1
                        || !writtenDottedOctaveHead(a.note, notes, k)
                        || !writtenDottedOctaveHead(b.note, notes, k)) break;
                var upper = a.y <= b.y ? a : b;
                var lower = upper == a ? b : a;
                attacks.add(new ClosedOctaveBeamSource.Attack(
                        upper.index, lower.index, upper.x, upper.y, lower.x, lower.y));
                directions.add(-a.note.stemDirection());
            }
            if (attacks.size() != 16) continue;
            if (octaves && directions.stream().allMatch(d -> d == 1)) {
                current = ClosedOctaveBeamSource.captureSeparateDottedOctaveGroups(
                        current, physicalPage, gray, original, width, height, gap, attacks);
            } else {
                current = ClosedOctaveBeamSource.captureDetachedDottedDyads(
                        current, physicalPage, gray, original, width, height, gap, attacks, directions);
            }
        }
        return current == score.beamSource() ? score : score.withBeamSource(current);
    }

    private static boolean writtenDottedOctaveHead(
            ScoreNoteEvent note, List<ScoreNoteEvent> notes, int column) {
        if (!regular(note)) return false;
        boolean dotted = (column & 1) == 0;
        var placement = ScoreNoteTiming.writtenPlacement(note, notes, 4f).orElse(null);
        return placement != null && !placement.grace()
                && placement.onsetBeats() == column / 2 * .5 + (dotted ? 0 : .375)
                && placement.durationBeats() == (dotted ? .375 : .125);
    }

    /** Identifies head-shaped regions contained by the beam of a closed dyad clock. */
    private static List<Head> joinedDyadRailAliases(Ink ink, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27 || bar.cadenceQuarterBeats() != 0) return List.of();
        var heads = new ArrayList<Head>(32);
        var aliases = new ArrayList<Head>(2);
        for (var head : row) {
            var note = head.note;
            if (note.stemDirection() == -1 && regular(note) && note.beamCount() >= 2) {
                heads.add(head);
            } else if (regularMarkings(note, 0)
                    && note.stemDirection() >= 0
                    && note.beamCount() == 0
                    && note.unbeamedDurationBeats() == 1
                    && note.writtenAccidental() == ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    && note.articulations() == 0) {
                aliases.add(head);
            } else {
                return List.of();
            }
        }
        if (heads.size() != 32 || aliases.size() != 2) return List.of();
        heads.sort(Comparator.comparingDouble(Head::x));
        var axes = new ArrayList<List<Shaft>>(16);
        int interval = Math.abs(heads.get(0).note.staffStep() - heads.get(1).note.staffStep());
        if (interval < 3 || interval > 7) return List.of();
        var budget = new PrintedBeamShafts.Budget(250_000);
        for (int i = 0; i < heads.size(); i += 2) {
            var first = heads.get(i);
            var second = heads.get(i + 1);
            var upper = first.y < second.y ? first : second;
            var lower = first == upper ? second : first;
            boolean dotted = i % 4 == 0;
            float next = i + 2 < heads.size() ? heads.get(i + 2).x : bar.right() * ink.w;
            if (second.x - first.x > .30f * gap
                    || lower.y - upper.y < 1.5f * gap
                    || upper.note.staffStep() - lower.note.staffStep() != interval
                    || first.note.clefBottomDiatonic() != second.note.clefBottomDiatonic()
                    || first.note.augmentationDots() != (dotted ? 1 : 0)
                    || second.note.augmentationDots() != (dotted ? 1 : 0)
                    || dot(ink, upper, next, gap) != dotted
                    || dot(ink, lower, next, gap) != dotted) return List.of();
            var shaft = commonShafts(ink, new Dyad(upper, lower), gap, budget);
            if (shaft.isEmpty()) return List.of();
            axes.add(shaft);
        }
        int previousOwner = -1;
        aliases.sort(Comparator.comparingDouble(Head::x));
        for (var alias : aliases) {
            if (!PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, alias.x, alias.y, gap, budget)
                    .isEmpty()) return List.of();
            int owner = -1;
            for (int i = 1; i < axes.size(); i += 2) {
                if (onJoinedDyadRails(ink, alias, gap, axes.get(i - 1), axes.get(i))) {
                    owner = i;
                    break;
                }
            }
            if (owner <= previousOwner || budget.refused()) return List.of();
            previousOwner = owner;
        }
        return aliases;
    }

    private static boolean onJoinedDyadRails(
            Ink ink, Head alias, float gap, List<Shaft> leading, List<Shaft> trailing) {
        int radius = Math.max(1, Math.round(.08f * gap));
        int margin = Math.round(.24f * gap);
        int reach = Math.round(.72f * gap);
        int centerX = Math.round(alias.x);
        int centerY = Math.round(alias.y);
        for (var left : leading)
            for (var right : trailing) {
                int distance = right.x - left.x;
                if (distance < 2 * gap
                        || distance > 4.5f * gap
                        || centerX < left.x + gap
                        || right.x - centerX < .17f * gap
                        || right.x - centerX > 1.3f * gap
                        || centerY < Math.min(left.tip, right.tip) - 1.55f * gap
                        || centerY > Math.max(left.tip, right.tip) + .26f * gap) continue;
                for (int start = left.tip - reach; start <= left.tip; start++) {
                    for (int end = right.tip - reach; end <= right.tip; end++) {
                        float slope = (end - start) / (float) distance;
                        if (Math.abs(slope) > .35f) continue;
                        int centerRail = Math.round(start + slope * (centerX - left.x));
                        for (int separation = Math.round(.55f * gap);
                                separation <= Math.round(.92f * gap);
                                separation++) {
                            if (centerY < centerRail - separation - margin
                                    || centerY > centerRail + margin) continue;
                            if (ownedRailInk(
                                    ink,
                                    left.x,
                                    right.x,
                                    centerX,
                                    centerY,
                                    start,
                                    slope,
                                    separation,
                                    radius,
                                    margin)) return true;
                        }
                    }
                }
            }
        return false;
    }

    private static boolean ownedRailInk(
            Ink ink,
            int left,
            int right,
            int centerX,
            int centerY,
            int start,
            float slope,
            int separation,
            int radius,
            int margin) {
        // A note-shaped region must be embedded horizontally in the ribbon, with the two
        // independent rails visible before their overlap with the staff or backward hook.
        int thickness = 2 * radius + 1;
        for (int x = centerX - margin; x <= centerX + margin; x++)
            if (ink.weight(x, centerY, radius) != thickness) return false;
        int both = 0;
        int separated = 0;
        int midpoint = (left + right) / 2;
        for (int x = left + margin; x <= right - margin; x++) {
            int primary = Math.round(start + slope * (x - left));
            if (ink.weight(x, primary, radius) != thickness
                    || ink.weight(x, primary - separation, radius) != thickness) continue;
            both++;
            if (x < midpoint && ink.weight(x, primary - separation / 2, radius) < thickness)
                separated++;
        }
        return both >= .93f * (right - left - 2 * margin + 1)
                && separated >= .35f * (midpoint - left - margin);
    }

    private static List<Dyad> closedOctaves(
            Ink ink,
            MeasureRegion prior,
            MeasureRegion bar,
            MeasureRegion following,
            List<Head> row,
            Head fragment,
            Dyad openingCadence) {
        boolean fragmentClock = fragment != null;
        float height = ink.h, left = bar.left() * ink.w, right = bar.right() * ink.w;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float next = following.left() * ink.w;
        if (gap < 10 || gap > 27 || right - left < gap * 32 || right - left > gap * 49)
            return List.of();
        if (openingCadence != null) {
            if (Math.abs(bar.top() - prior.top()) * height > gap
                    || Math.abs(bar.bottom() - prior.bottom()) * height > gap
                    || (following.top() - bar.bottom()) * height < 3 * gap
                    || left - prior.right() * ink.w < gap * .25f
                    || left - prior.right() * ink.w > gap * 1.5f
                    || right + gap >= ink.w) return List.of();
            next = right + gap * .8f;
        } else if ((bar.top() - prior.bottom()) * height < gap * 3
                || Math.abs(following.top() - bar.top()) * height > gap
                || next - right < gap * .3f
                || next - right > gap * 1.75f) return List.of();
        var sorted = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var dyads = new ArrayList<Dyad>(16);
        boolean needsRepair = false;
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            boolean dotted = i % 4 == 0;
            if (!octaveClockOwner(upper.note, dotted, fragmentClock)
                    || !octaveClockOwner(lower.note, dotted, fragmentClock)
                    || upper.note.stemDirection() != -1
                    || lower.note.stemDirection() != -1
                    || upper.note.clefBottomDiatonic() != row.get(0).note.clefBottomDiatonic()
                    || lower.note.clefBottomDiatonic() != upper.note.clefBottomDiatonic()
                    || upper.note.staffStep() - lower.note.staffStep() != 7
                    || !fragmentClock && upper.note.octaveShift() != lower.note.octaveShift()
                    || b.x - a.x > gap * .24f
                    || lower.y - upper.y < gap * 3.18f
                    || lower.y - upper.y > gap * 3.8f) return List.of();
            if (!dyads.isEmpty()) {
                float distance = lower.x - dyads.get(dyads.size() - 1).lower.x;
                if (distance < gap * 1.50f || distance > gap * 4.15f) return List.of();
            }
            needsRepair |=
                    rhythm(upper.note, dotted) != upper.note
                            || rhythm(lower.note, dotted) != lower.note;
            dyads.add(new Dyad(upper, lower));
        }
        if (!needsRepair) return List.of();
        var track = readOctaveStaff(ink, left, right, bar.bottom() * height - 2.1f * gap, gap);
        if (track == null) return List.of();
        float first = dyads.get(0).lower.x, last = dyads.get(15).lower.x;
        if (first - left < gap * (openingCadence == null ? 1.7f : 1.1f)
                || first - left > gap * 3.4f
                || !closingStroke(ink, track, right, next, last, gap)) return List.of();
        if (openingCadence != null && (fragment.x <= first || fragment.x >= dyads.get(1).lower.x))
            return List.of();
        var axes = new ArrayList<List<Shaft>>(16);
        var budget = new PrintedBeamShafts.Budget(250_000);
        if (fragmentClock
                && !PrintedBeamShafts.at(
                                ink.pixels, ink.w, ink.h, fragment.x, fragment.y, gap, budget)
                        .isEmpty()) return List.of();
        var needsRails = new boolean[dyads.size()];
        for (int i = 0; i < dyads.size(); i++) {
            var dyad = dyads.get(i);
            float after = i + 1 < dyads.size() ? dyads.get(i + 1).upper.x : right;
            boolean dotted = i % 2 == 0;
            if (openingCadence == null) {
                boolean upperDot = dotAgreement(ink, dyad.upper, after, gap, dotted);
                boolean lowerDot = dotAgreement(ink, dyad.lower, after, gap, dotted);
                if ((!fragmentClock || !dotted) ? !upperDot || !lowerDot : !upperDot && !lowerDot)
                    return List.of();
            }
            if (openingCadence != null
                    && dotted
                    && (dyad.upper.note.beamCount() != 2 || dyad.lower.note.beamCount() != 2))
                return List.of();
            // The terminal run separately proves each under-read hook. Resolve shared axes
            // here only where the alias ribbon or a group division consumes them.
            boolean needsAxes = openingCadence == null || i < 2 || i % 4 == 0 || i % 4 == 3;
            List<Shaft> partners = needsAxes ? commonShafts(ink, dyad, gap, budget) : List.of();
            boolean changing =
                    rhythm(dyad.upper.note, dotted) != dyad.upper.note
                            || rhythm(dyad.lower.note, dotted) != dyad.lower.note;
            if (needsAxes && partners.isEmpty() && (!fragmentClock || changing)) return List.of();
            needsRails[i] = fragmentClock && dotted && changing;
            if (!dotted
                    && (dyad.upper.note.beamCount() < 3 || dyad.lower.note.beamCount() < 3)
                    && !provesRestoredHook(ink, dyad, dyads.get(i - 1), gap, budget))
                return List.of();
            axes.add(partners);
        }
        if (axes.get(0).isEmpty()) return List.of();
        int firstShaft = axes.get(0).stream().mapToInt(Shaft::x).min().orElseThrow();
        if (openingCadence == null) {
            if (!paperBetweenHeaderAndShaft(ink, track, left, firstShaft, gap)) return List.of();
        } else if (!closingStroke(
                ink, track, prior.right() * ink.w, left, openingCadence.lower.x, gap)) {
            return List.of();
        }
        boolean boundFragment = false;
        for (int i = 0; i + 1 < dyads.size(); i++) {
            var a = axes.get(i);
            var b = axes.get(i + 1);
            var leftDyad = dyads.get(i).lower;
            var rightDyad = dyads.get(i + 1).lower;
            if (i % 4 == 3) {
                if (a.isEmpty() || b.isEmpty()) return List.of();
                if (!gapBetween(ink, leftDyad, rightDyad, a, b, gap, track)) return List.of();
                continue;
            }
            Head suspect =
                    fragmentClock && leftDyad.x < fragment.x && fragment.x < rightDyad.x
                            ? fragment
                            : null;
            if (!fragmentClock || suspect != null || needsRails[i] || needsRails[i + 1]) {
                if (!ownedLink(ink, a, b, i, gap, track, suspect, true)) return List.of();
                boundFragment |= suspect != null;
            }
        }
        return fragmentClock && (!boundFragment || budget.refused()) ? List.of() : dyads;
    }

    private static List<Dyad> halfOctaveCadence(Ink ink, MeasureRegion bar, List<Head> row) {
        if (row.size() != 18 || bar.cadenceQuarterBeats() != 0) return List.of();
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27) return List.of();
        var sorted = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        var pairs = new ArrayList<Dyad>();
        var trial = new ArrayList<ScoreNoteEvent>();
        var budget = new PrintedBeamShafts.Budget(160_000);
        for (int i = 0; i < sorted.size(); i += 2) {
            var a = sorted.get(i);
            var b = sorted.get(i + 1);
            var upper = a.y < b.y ? a : b;
            var lower = upper == a ? b : a;
            boolean half = i == 16;
            boolean dotted = i % 4 == 0 && !half;
            if (!cadenceHead(upper.note, half, dotted)
                    || !cadenceHead(lower.note, half, dotted)
                    || upper.note.staffStep() - lower.note.staffStep() != 7
                    || upper.note.clefBottomDiatonic() != lower.note.clefBottomDiatonic()
                    || b.x - a.x > gap * .24f
                    || lower.y - upper.y < gap * 3.18f
                    || lower.y - upper.y > gap * 3.8f) return List.of();
            var pair = new Dyad(upper, lower);
            if (!half) {
                if (!dotted
                        && (upper.note.beamCount() < 3 || lower.note.beamCount() < 3)
                        && !provesRestoredHook(ink, pair, pairs.get(pairs.size() - 1), gap, budget))
                    return List.of();
            }
            pairs.add(pair);
            trial.add(half ? upper.note : rhythm(upper.note, dotted));
            trial.add(half ? lower.note : rhythm(lower.note, dotted));
        }
        for (int i = 0; i < trial.size(); i++) {
            int slot = i / 2;
            boolean half = slot == 8;
            boolean dotted = slot % 2 == 0;
            var p = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4).orElse(null);
            if (p == null
                    || p.onsetBeats() != (half ? 2 : slot / 2 * .5 + (dotted ? 0 : .375))
                    || p.durationBeats() != (half ? 2 : dotted ? .375 : .125)) return List.of();
        }
        return budget.refused() ? List.of() : pairs;
    }

    private static boolean cadenceHead(ScoreNoteEvent n, boolean half, boolean dotted) {
        if (!regularMarkings(n, 1)
                || n.stemDirection() != -1
                || n.augmentationDots() != (dotted ? 1 : 0)) return false;
        if (half) return n.beamCount() == 0 && n.unbeamedDurationBeats() == 2;
        return n.unbeamedDurationBeats() == 0
                && (n.beamCount() == 2 || !dotted && n.beamCount() == 3);
    }

    private static boolean octaveClockOwner(
            ScoreNoteEvent note, boolean dotted, boolean fragmentClock) {
        if (!fragmentClock) return regular(note, 2) && note.beamCount() >= 2;
        // Keep the declared alternating dot phase. Only the physically proved rails may change.
        return regularMarkings(note, 1)
                && note.augmentationDots() == (dotted ? 1 : 0)
                && (note.beamCount() != 0 && note.unbeamedDurationBeats() == 0
                        || !dotted && note.beamCount() == 0 && note.unbeamedDurationBeats() == 1)
                && (!dotted || note.beamCount() <= 2);
    }

    private static StaffPitchTrack readOctaveStaff(
            Ink ink, float left, float right, float hint, float gap) {
        var samples = new ArrayList<float[]>();
        for (int i = 0; i <= 12; i++) {
            float x = left + (right - left) * i / 12;
            var local = localRules(ink, x, hint, gap);
            if (local != null) samples.add(new float[] {x, local[0], local[1]});
        }
        return samples.size() >= 5
                        && samples.get(0)[0] <= left + (right - left) * .27f
                        && samples.get(samples.size() - 1)[0] >= right - (right - left) * .27f
                ? StaffPitchTrack.fromVerifiedSamples(samples)
                : null;
    }

    static boolean hasClosingRule(byte[] gray, int width, int height, MeasureRegion bar) {
        if (gray == null || width < 256 || height < 128 || gray.length != (long) width * height)
            return false;
        var ink = new Ink(gray, width, height);
        float left = bar.left() * width, right = bar.right() * width;
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 27) return false;
        var staff = readOctaveStaff(ink, left, right, bar.bottom() * height - 2.1f * gap, gap);
        return staff != null && barline(ink, right, staff.at(right));
    }

    private static List<Shaft> commonShafts(
            Ink ink, Dyad dyad, float gap, PrintedBeamShafts.Budget budget) {
        var upper = downwardShaft(ink, dyad.upper, gap, budget);
        var lower = downwardShaft(ink, dyad.lower, gap, budget);
        if (upper == null || lower == null) return List.of();
        var shared = new ArrayList<Shaft>();
        for (var a : upper.columns())
            for (var b : lower.columns())
                if (a.x() == b.x() && Math.abs(a.endY() - b.endY()) <= 1)
                    shared.add(new Shaft(a.x(), Math.max(a.endY(), b.endY())));
        return shared;
    }

    private static PrintedBeamShafts.Shaft downwardShaft(
            Ink ink, Head head, float gap, PrintedBeamShafts.Budget budget) {
        var at = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, head.x, head.y, gap, budget);
        return at.size() == 1 && at.get(0).direction() == 1 ? at.get(0) : null;
    }

    private static boolean provesRestoredHook(
            Ink ink, Dyad own, Dyad peer, float gap, PrintedBeamShafts.Budget budget) {
        return OwnedTertiaryBeamlets.provesClosingDyadHook(
                ink.pixels,
                ink.w,
                ink.h,
                own.lower.x,
                own.lower.y,
                own.upper.x,
                own.upper.y,
                peer.upper.x,
                peer.upper.y,
                peer.lower.x,
                peer.lower.y,
                gap,
                budget);
    }

    private static boolean dotAgreement(Ink ink, Head head, float next, float gap, boolean dotted) {
        var first =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels, ink.w, ink.h, head.x, head.y, next, gap);
        boolean separate =
                LegacyTripletDotInk.singleDotCore(
                        ink.pixels, ink.w, ink.h, head.x, head.y, next, gap);
        if (!dotted) return first == null && !separate;
        if (first == null || !separate) return false;
        // The next shaft can crop a genuine second dot out of the ordinary single-dot scan.
        // Check the remaining paper beside the first dot, short of the next head's own marks.
        var second =
                WrappedTripletDotInk.uniqueCore(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        first.x() - gap * .42f,
                        first.y() + gap * .18f,
                        next + gap * .33f,
                        gap);
        return second == null || Math.abs(second.y() - first.y()) > gap * .34f;
    }

    private static boolean paperBetweenHeaderAndShaft(
            Ink ink, StaffPitchTrack track, float crop, int shaft, float gap) {
        int start = Math.round(crop - gap * .32f), end = shaft - Math.round(gap * .22f);
        if (end - start < gap * 1.45f) return false;
        for (int x = start; x <= end; x++) {
            float[] f = track.at(x);
            if (staffSpaces(ink, x, f, .24f) > 1) return false;
            if ((x == start || x == end) && !fiveRulesAt(ink, x, f)) return false;
        }
        return true;
    }

    private static boolean fiveRulesAt(Ink ink, int x, float[] frame) {
        for (int i = 0; i < 5; i++)
            if (ink.weight(x, Math.round(frame[0] - i * frame[1]), 2) < 1) return false;
        return true;
    }

    private static boolean closingStroke(
            Ink ink, StaffPitchTrack track, float edge, float next, float last, float gap) {
        return singleStaffStroke(ink, track, edge, next, last + gap * 1.3f, gap);
    }

    private static boolean singleStaffStroke(
            Ink ink, StaffPitchTrack track, float edge, float next, float after, float gap) {
        int run = 0, strokes = 0;
        for (int x = Math.round(edge); x <= Math.round(next) + 1; x++) {
            float[] frame = track.at(x);
            int bottom = Math.round(frame[0]), top = Math.round(frame[0] - 4 * frame[1]);
            if (x <= Math.round(next)
                    && x > after
                    && staffSpaces(ink, x, frame, .90f) == 4
                    && !DottedDyadMeasureSeparator.continuesBeyondStaff(
                            ink.pixels, ink.w, ink.h, x, top, bottom, gap)) run++;
            else {
                if (run >= Math.max(2, Math.round(gap * .12f))) strokes++;
                run = 0;
            }
        }
        return strokes == 1;
    }

    private static int staffSpaces(Ink ink, int x, float[] frame, float fill) {
        int occupied = 0;
        for (int i = 0; i < 4; i++) {
            int from = Math.round(frame[0] - (i + .73f) * frame[1]);
            int to = Math.round(frame[0] - (i + .27f) * frame[1]);
            int dark = 0;
            for (int y = from; y <= to; y++) if (ink.dark(x, y)) dark++;
            if (dark >= (to - from + 1) * fill) occupied++;
        }
        return occupied;
    }

    private static boolean fillsDottedClock(List<ScoreNoteEvent> trial, float meter) {
        for (int i = 0; i < trial.size(); i++) {
            int attack = i / 2;
            boolean dotted = attack % 2 == 0;
            var placement =
                    ScoreNoteTiming.writtenPlacement(trial.get(i), trial, meter).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != attack / 2 * .5 + (dotted ? 0 : .375)
                    || placement.durationBeats() != (dotted ? .375 : .125)) return false;
        }
        return true;
    }

    private record Proof(Head fragment,List<Head> kept) {}

    /** A stemless fragment inside an up-stem beam ribbon has no independent attack. */
    private static Proof closedUpperRibbon(Ink raw, Ink normalized, MeasureRegion bar, List<Head> row) {
        float gap = (bar.bottom() - bar.top()) * raw.h / 8.2f;
        if (gap < 10 || gap > 24 || bar.cadenceQuarterBeats() != 0
                || (bar.right() - bar.left()) * raw.w < 29 * gap
                || (bar.right() - bar.left()) * raw.w > 37 * gap) return null;
        var fragments = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (fragments.size() != 1) return null;
        var fragment = fragments.get(0);
        var n = fragment.note;
        if (!regularMarkings(n, 0) || n.beamCount() != 0 || n.unbeamedDurationBeats() != 1
                || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY || n.articulations() != 0)
            return null;
        var owners = row.stream().filter(h -> h != fragment).sorted(Comparator.comparingDouble(Head::x)).toList();
        for (int i = 0; i < owners.size(); i++) {
            var head = owners.get(i);
            var note = head.note;
            boolean dotted = i % 2 == 0;
            if (!regular(note) || note.stemDirection() != 1 || note.augmentationDots() != (dotted ? 1 : 0)
                    || note.beamCount() < 2 || note.beamCount() > (dotted ? 2 : 3)
                    || note.clefBottomDiatonic() != n.clefBottomDiatonic()) return null;
            if (i > 0) {
                float advance = head.x - owners.get(i - 1).x;
                if (advance < 1.65f * gap || advance > 3.9f * gap) return null;
            }
        }
        if (!upperRibbonOptics(raw, bar, owners, fragment, gap)
                || !upperRibbonOptics(normalized, bar, owners, fragment, gap)) return null;
        return new Proof(fragment, owners);
    }

    private static boolean upperRibbonOptics(Ink ink, MeasureRegion bar, List<Head> notes,
            Head fragment, float gap) {
        var track = WrappedMeasureBoundaries.verifiedTripletStaff(ink.pixels, ink.w, ink.h, bar);
        float right = bar.right() * ink.w;
        if (track == null || !barline(ink, right, track.at(right))) return false;
        var above = new ArrayList<PrintedBeamShafts.Shaft>(notes.size());
        var budget = new PrintedBeamShafts.Budget(180_000);
        for (int i = 0; i < notes.size(); i++) {
            var head = notes.get(i);
            float next = i + 1 < notes.size() ? notes.get(i + 1).x : right;
            if (dot(ink, head, next, gap) != (i % 2 == 0)) return false;
            var shafts = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, head.x, head.y, gap, budget);
            if (shafts.size() != 1 || shafts.get(0).direction() != -1 || budget.refused()) return false;
            above.add(shafts.get(0));
            if (i % 2 == 1) {
                var prior = notes.get(i - 1);
                if (!OwnedTertiaryBeamlets.provesMonophonicBackwardHook(ink.pixels, ink.w, ink.h,
                        new OwnedTertiaryBeamlets.Head(head.index, head.note, head.x, head.y, gap),
                        new OwnedTertiaryBeamlets.Head(prior.index, prior.note, prior.x, prior.y, gap), gap))
                    return false;
            }
        }
        if (!PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, fragment.x, fragment.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        for (int i = 0; i + 1 < above.size(); i++) {
            var first = above.get(i);
            var next = above.get(i + 1);
            if (i % 4 == 3 && !upperRibbonBreak(ink, first, next, track, gap)) return false;
        }
        return upperRibbonFragment(ink, fragment, above.get(10), above.get(11), track, gap);
    }

    private static boolean upperRibbonBreak(Ink ink, PrintedBeamShafts.Shaft left,
            PrintedBeamShafts.Shaft right, StaffPitchTrack track, float gap) {
        int x = (left.right() + right.left()) / 2;
        int top = Math.min(left.endY(), right.endY());
        int bottom = Math.max(left.endY(), right.endY()) + Math.round(1.35f * gap);
        int dark = 0, samples = 0;
        for (int c = x - 2; c <= x + 2; c++) {
            var rules = track.at(c);
            for (int y = top; y <= bottom; y++) {
                if (onRule(rules, y, .17f)) continue;
                if (ink.dark(c, y)) dark++;
                samples++;
            }
        }
        return samples > 35 && dark * 7 < samples;
    }

    private static boolean upperRibbonFragment(Ink ink, Head h, PrintedBeamShafts.Shaft left,
            PrintedBeamShafts.Shaft right, StaffPitchTrack track, float gap) {
        int x1 = left.right(), x2 = right.left();
        if (h.x - x1 < gap || x2 - h.x < gap) return false;
        float slope = (right.endY() - left.endY()) / (float)(x2 - x1);
        if (Math.abs(slope) > .35f) return false;
        float outer = left.endY() + (h.x - x1) * slope;
        if (h.y - outer < gap * .45f || h.y - outer > gap * .96f) return false;
        int support = 0, clearance = 0;
        int start = Math.round(h.x - .78f * gap), end = Math.round(h.x + .78f * gap);
        for (int x = start; x <= end; x++) {
            float local = outer + (x - h.x) * slope;
            int center = Math.round(h.y + (x - h.x) * slope);
            if (ink.weight(x, center, 2) >= 2) support++;
            var rules = track.at(x);
            for (int y = Math.round(local - .55f * gap); y <= Math.round(local + 1.68f * gap); y++) {
                if (y >= local - .15f * gap && y <= local + 1.42f * gap || onRule(rules, y, .15f)) continue;
                if (ink.dark(x, y)) clearance++;
            }
        }
        return support >= (end - start + 1) * .90f && clearance <= gap * .7f;
    }

    private static Proof prove(Ink ink,MeasureRegion bar,List<Head> row) {
        if(bar.cadenceQuarterBeats()!=0 || row.stream().anyMatch(h->!regular(h.note))) return null;
        float left=bar.left()*ink.w,right=bar.right()*ink.w;
        float gap=(bar.bottom()-bar.top())*ink.h/8.2f;
        if (gap<10 || gap>27 || right-left<28*gap || right-left>39*gap) return null;
        var candidates=row.stream().filter(h->h.note.stemDirection()==0).toList();
        if(candidates.size()!=1) return null;
        var fragment=candidates.get(0);
        if(fragment.note.beamCount()!=3 || fragment.note.augmentationDots()!=0
                || fragment.note.articulations()!=0) return null;
        var owners=row.stream().filter(h->h!=fragment).sorted(Comparator.comparingDouble(Head::x)).toList();
        if(owners.stream().anyMatch(h->h.note.stemDirection()!=-1
                || h.note.clefBottomDiatonic()!=owners.get(0).note.clefBottomDiatonic())) return null;
        if(owners.get(0).x-left<gap*1.4f || owners.get(0).x-left>gap*3.25f
                || right-owners.get(11).x<gap*.85f || right-owners.get(11).x>gap*2.1f) return null;
        var samples=new ArrayList<float[]>();
        for(int i=0;i<=12;i++) {
            float x=left+(right-left)*i/12;
            var near=owners.stream().min(Comparator.comparingDouble(a->Math.abs(a.x-x))).orElseThrow();
            float hint=near.y+near.note.staffStep()*gap*.5f;
            var rules=localRules(ink,x,hint,gap);
            if(rules!=null) samples.add(new float[]{x,rules[0],rules[1]});
        }
        var track=samples.size()>=4?StaffPitchTrack.fromVerifiedSamples(samples):null;
        // The narrow right rule closes the run. The first gap can be a system header.
        if(track==null || !barline(ink,right,track.at(right))
                || PrintedStemDirection.detect(ink.pixels,ink.w,ink.h,fragment.x,fragment.y,gap)!=0)
            return null;
        gap=track.at((left+right)*.5f)[1];
        int fragmentGroup=-1;
        List<List<Shaft>> axes=new ArrayList<>();
        for(int i=0;i<owners.size();i++) {
            var a=owners.get(i);
            float next=i+1<owners.size()?owners.get(i+1).x:right;
            if(i<11 && (next-a.x<gap*1.50f || next-a.x>gap*4.15f)) return null;
            var aShafts=shafts(ink,a,gap);
            if(aShafts.isEmpty()) return null;
            axes.add(aShafts);
            if(dot(ink,a,next,gap)!=(i%2==0)) return null;
            if(i%4==0 && Math.abs(fragment.x-a.x)<=gap*.31f && fragment.y-a.y>gap*3)
                fragmentGroup=i;
        }
        if(fragmentGroup<0 || !shafts(ink,fragment,gap).isEmpty()) return null;
        for(int i=0;i<11;i++) {
            var a=owners.get(i);
            var b=owners.get(i+1);
            if(i%4==3) {
                if(!gapBetween(ink,a,b,axes.get(i),axes.get(i+1),gap,track)) return null;
            } else if (!ownedLink(ink,axes.get(i),axes.get(i+1),i,gap,track,
                    i==fragmentGroup?fragment:null)) return null;
        }
        var trial=new ArrayList<ScoreNoteEvent>();
        for(int i=0;i<owners.size();i++) trial.add(rhythm(owners.get(i).note,i%2==0));
        for(int i=0;i<trial.size();i++) {
            var p=ScoreNoteTiming.writtenPlacement(trial.get(i),trial,3f).orElse(null);
            if(p==null || p.onsetBeats()!=i/2*.5+(i%2==1?.375:0)
                    || p.durationBeats()!=(i%2==0?.375:.125)) return null;
        }
        return new Proof(fragment,owners);
    }

    /** Two eighths lead six dotted pairs in four independently separated, common-time groups. */
    static boolean provesEighthAndDottedGroups(List<ScoreNoteEvent> notes, List<Integer> owners,
            List<Integer> guests, MeasureRegion bar, byte[] raw, byte[] normalized, int width, int height) {
        var heads = owners.stream().map(i -> dottedGroupHead(notes, i, bar, width, height)).toList();
        var aliases = guests.stream().map(i -> dottedGroupHead(notes, i, bar, width, height)).toList();
        for (int slot = 0; slot < heads.size(); slot++) {
            var note = heads.get(slot).note;
            int depth = slot % 7 == 0 ? 1 : 2;
            if (!regular(note, 1) || note.clefBottomDiatonic() != heads.get(0).note.clefBottomDiatonic()
                    || (slot % 7 == 0 && note.augmentationDots() != 0)
                    || note.beamCount() != depth && !(slot % 7 > 0 && slot % 7 % 2 == 0 && note.beamCount() == 3))
                return false;
        }
        for (var alias : aliases) {
            var n = alias.note;
            if (!regularMarkings(n, 0) || n.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || n.clefBottomDiatonic() != heads.get(0).note.clefBottomDiatonic()
                    || (n.articulations() & ~NoteArticulation.STACCATO) != 0) return false;
        }
        var original = new Ink(raw, width, height);
        return provesEighthAndDottedGroups(original, bar, heads, aliases)
                && (raw == normalized
                        || provesEighthAndDottedGroups(new Ink(normalized, width, height), bar, heads, aliases));
    }

    private static Head dottedGroupHead(List<ScoreNoteEvent> notes, int index, MeasureRegion bar, int w, int h) {
        var note = notes.get(index);
        return new Head(index, note, (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * w,
                note.pageY() * h);
    }

    private static boolean provesEighthAndDottedGroups(
            Ink ink, MeasureRegion bar, List<Head> heads, List<Head> aliases) {
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        float right = bar.right() * ink.w;
        if (gap < 10 || gap > 25 || bar.cadenceQuarterBeats() != 0 || !hasClosingRule(ink.pixels, ink.w, ink.h, bar))
            return false;
        var budget = new PrintedBeamShafts.Budget(420_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>();
        var stems = new ArrayList<PairedTremoloInk.Stem>();
        for (int i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var found = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, head.x, head.y, gap, budget);
            if (budget.refused() || found.size() != 1 || found.get(0).direction() != 1) return false;
            var shaft = found.get(0);
            if (i > 0) {
                var prior = axes.get(i - 1);
                float distance = head.x - heads.get(i - 1).x;
                int rails = i == 3 || i == 7 || i == 10 ? 0 : i == 1 || i == 8 ? 1 : 2;
                if (distance < 1.55f * gap || distance > 4.25f * gap
                        || !(rails == 0
                                ? WrappedEighthTriplets.cutBetweenRecoveredGroups(
                                        ink.pixels, ink.w, ink.h, bar, prior, shaft, gap)
                                : WrappedEighthTriplets.matchesBeamRails(
                                        ink.pixels, ink.w, ink.h, prior, shaft, gap, rails))) return false;
            }
            axes.add(shaft);
            var stem = PairedTremoloInk.stem(ink.pixels, ink.w, ink.h, head.x, head.y, gap, -1);
            if (stem == null) return false;
            stems.add(stem);
            if (i % 7 == 0) continue;
            float next = i + 1 < heads.size() ? heads.get(i + 1).x : right;
            if (i % 7 % 2 == 1) {
                next = Math.min(next, head.x + 2.23f * gap);
                var mark = WrappedTripletDotInk.singleAugmentationCore(
                        ink.pixels, ink.w, ink.h, head.x, head.y, next, gap);
                if (mark == null) mark = WrappedTripletDotInk.singleAugmentationCore(
                        ink.pixels, ink.w, ink.h, head.x + .24f * gap, head.y, next, gap);
                if (mark == null || mark.x() - head.x < .98f * gap) return false;
            } else if (!provesBackwardDottedHook(ink, head, heads.get(i - 1), gap)
                    && !compactDownstemHook(ink, stems.get(i - 1), stem, gap, -1)) {
                return false;
            }
        }

        for (var alias : aliases) {
            if (staffAttachedGlyph(ink, alias, heads.get(10), stems.get(10), gap)) continue;
            boolean owned = false;
            for (int i = 2; i < heads.size(); i++) {
                if (i == 3 || i == 7 || i == 8 || i == 10
                        || alias.x <= stems.get(i - 1).x() || alias.x > stems.get(i).x() + 1) continue;
                if (ribbonInkOwnsAlias(ink, alias, heads.get(i - 1), heads.get(i), stems.get(i - 1), stems.get(i), gap)
                        || attachedShortHookAlias(ink, alias, heads.get(i), stems.get(i - 1), stems.get(i), gap)) {
                    owned = true;
                    break;
                }
            }
            if (!owned) return false;
        }
        return true;
    }

    private static boolean attachedShortHookAlias(Ink ink, Head alias, Head hook,
            PairedTremoloInk.Stem left, PairedTremoloInk.Stem right, float gap) {
        float distance = right.x() - alias.x;
        if (distance < .13f * gap || distance > .55f * gap || alias.y - hook.y < 2.4f * gap
                || alias.note.stemDirection() == -1 || right.x() <= left.x()) return false;
        var budget = new PrintedBeamShafts.Budget(24_000);
        var claims = PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, alias.x, alias.y, gap, budget);
        if (budget.refused() || !claims.isEmpty()) return false;
        float slope = (right.endY() - left.endY()) / (float) (right.x() - left.x());
        float depth = right.endY() - slope * distance - alias.y;
        if (depth < 1.45f * gap || depth > 2.40f * gap
                || !compactDownstemHook(ink, left, right, gap, -1)) return false;
        int y = Math.round(alias.y);
        for (int x = Math.round(alias.x); x <= right.x(); x++)
            if (ink.weight(x, y, 2) < 4) return false;
        return true;
    }

    /** A tall glyph crossing the ruled floor below a proved stem is not a second attack. */
    private static boolean staffAttachedGlyph(
            Ink ink, Head alias, Head owner, PairedTremoloInk.Stem stem, float gap) {
        if (alias.note.stemDirection() != 0 || Math.abs(alias.x - owner.x) > .20f * gap
                || alias.y - stem.endY() < 1.25f * gap) return false;
        var rules = localRules(ink, owner.x, owner.y + owner.note.staffStep() * gap * .5f, gap);
        if (rules == null || alias.y - rules[0] < .30f * gap || alias.y - rules[0] > .85f * gap) return false;
        var budget = new PrintedBeamShafts.Budget(24_000);
        if (!PrintedBeamShafts.at(ink.pixels, ink.w, ink.h, alias.x, alias.y, gap, budget).isEmpty()
                || budget.refused()) return false;
        int left = Math.round(owner.x - .66f * gap), right = Math.round(owner.x + .66f * gap);
        int top = Math.round(rules[0] - .40f * gap), bottom = Math.round(rules[0] + 1.34f * gap);
        if (left < 2 || right + 2 >= ink.w || top < 0 || bottom >= ink.h) return false;
        int minX = right, maxX = left, minY = bottom, maxY = top, above = 0, below = 0;
        for (int y = top; y <= bottom; y++) {
            boolean ruled = Math.abs(y - rules[0]) <= .22f * gap;
            if (ruled && (ink.pixels[y * ink.w + left - 1] & 255) < 165
                    && (ink.pixels[y * ink.w + right + 1] & 255) < 165) continue;
            for (int x = left; x <= right; x++) {
                if (!ink.dark(x, y)) continue;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                if (y < rules[0]) above++; else below++;
            }
        }
        return above >= .08f * gap * gap && below >= .25f * gap * gap
                && minX > left && maxX < right && minY > top && maxY < bottom
                && maxX - minX >= .51f * gap && maxY - minY >= 1.08f * gap
                && Math.abs((minX + maxX) * .5f - alias.x) <= .28f * gap;
    }


    /** Two eighth-note entries each lead three dotted-sixteenth and hook pairs. */
    private static boolean closedEighthAndDottedPairs(
            Ink ink, MeasureRegion bar, List<Head> row, ScoreNoteEvent[] replacement) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !regular(h.note)))
            return false;
        float left = bar.left() * ink.w, right = bar.right() * ink.w;
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27 || right - left < 32 * gap || right - left > 45 * gap)
            return false;
        var owners = row.stream().sorted(Comparator.comparingDouble(Head::x)).toList();
        if (owners.get(0).x - left < gap * 1.35f
                || owners.get(0).x - left > gap * 4.1f + 1f
                || right - owners.get(13).x < gap * .85f
                || right - owners.get(13).x > gap * 2.3f) return false;
        var track = readOctaveStaff(ink, left, right, bar.bottom() * ink.h - 2.1f * gap, gap);
        if (track == null || !barline(ink, right, track.at(right))) return false;
        var trial = new ArrayList<ScoreNoteEvent>(owners.size());
        for (int i = 0; i < owners.size(); i++) {
            var head = owners.get(i);
            float next = i + 1 < owners.size() ? owners.get(i + 1).x : right;
            int slot = i % 7;
            var note = head.note;
            boolean dotted = slot % 2 == 1;
            if (note.stemDirection() == 0
                    || note.clefBottomDiatonic() != owners.get(0).note.clefBottomDiatonic()
                    || i + 1 < owners.size()
                            && (next - head.x < gap * 1.5f || next - head.x > gap * 4.2f)
                    || dot(ink, head, next, gap) != dotted) return false;
            if (slot == 0) {
                if (note.beamCount() != 1 || note.augmentationDots() != 0) return false;
                trial.add(note);
                continue;
            }
            if (dotted) {
                if (note.beamCount() != 2 || note.augmentationDots() != 1) return false;
                trial.add(note);
                continue;
            }
            var prior = owners.get(i - 1);
            if (note.beamCount() < 2 || prior.note.stemDirection() != note.stemDirection())
                return false;
            if (note.beamCount() != 3 || note.augmentationDots() != 0) {
                boolean backwardHook =
                        note.stemDirection() == -1
                                ? OwnedTertiaryBeamlets.provesBackwardHookOnJoinedRails(
                                        ink.pixels,
                                        ink.w,
                                        ink.h,
                                        head.x,
                                        head.y,
                                        prior.x,
                                        prior.y,
                                        gap)
                                : OwnedTertiaryBeamlets.provesMonophonicBackwardHook(
                                        ink.pixels,
                                        ink.w,
                                        ink.h,
                                        new OwnedTertiaryBeamlets.Head(
                                                head.index, head.note, head.x, head.y, gap),
                                        new OwnedTertiaryBeamlets.Head(
                                                prior.index, prior.note, prior.x, prior.y, gap),
                                        gap);
                if (!backwardHook) return false;
            }
            trial.add(rhythm(note, false));
        }
        for (int i = 0; i < trial.size(); i++) {
            int slot = i % 7;
            float start = i / 7 * 2f;
            float duration = .5f;
            if (slot > 0) {
                boolean dotted = slot % 2 == 1;
                start += .5f + (slot - 1) / 2 * .5f + (dotted ? 0 : .375f);
                duration = dotted ? .375f : .125f;
            }
            var placement = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4f).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != start
                    || placement.durationBeats() != duration) return false;
        }
        boolean changed = false;
        for (int i = 0; i < trial.size(); i++) {
            var owner = owners.get(i);
            if (trial.get(i) != owner.note) {
                replacement[owner.index] = trial.get(i);
                changed = true;
            }
        }
        return changed;
    }

    /** Restores missing hooks in a complete monophonic run closed by a printed barline. */
    private static Proof closedCommonTimeRun(Ink ink, MeasureRegion bar, List<Head> row) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !regular(h.note)))
            return null;
        float left = bar.left() * ink.w, right = bar.right() * ink.w;
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27 || right - left < 32 * gap || right - left > 44 * gap) return null;
        var stemless = row.stream().filter(h -> h.note.stemDirection() == 0).toList();
        if (stemless.size() != 1) return null;
        var fragment = stemless.get(0);
        if (fragment.note.beamCount() != 3
                || fragment.note.augmentationDots() != 0
                || fragment.note.articulations() != 0
                || PrintedStemDirection.detect(
                                ink.pixels, ink.w, ink.h, fragment.x, fragment.y, gap)
                        != 0) return null;
        var owners =
                row.stream()
                        .filter(h -> h != fragment)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (owners.get(0).x - left < gap * 1.2f
                || owners.get(0).x - left > gap * 3.5f
                || right - owners.get(15).x < gap * .85f
                || right - owners.get(15).x > gap * 2.1f) return null;
        var track = readOctaveStaff(ink, left, right, bar.bottom() * ink.h - 2.1f * gap, gap);
        if (track == null || !barline(ink, right, track.at(right))) return null;
        boolean alias = false;
        var trial = new ArrayList<ScoreNoteEvent>(owners.size());
        for (int i = 0; i < owners.size(); i++) {
            var head = owners.get(i);
            float next = i + 1 < owners.size() ? owners.get(i + 1).x : right;
            boolean dotted = i % 2 == 0;
            if (head.note.stemDirection() != -1
                    || head.note.clefBottomDiatonic() != owners.get(0).note.clefBottomDiatonic()
                    || head.note.augmentationDots() != (dotted ? 1 : 0)
                    || head.note.beamCount() < 2
                    || dotted && head.note.beamCount() != 2
                    || i + 1 < owners.size()
                            && (next - head.x < gap * 1.5f || next - head.x > gap * 4.2f)
                    || dot(ink, head, next, gap) != dotted) return null;
            if (!dotted && head.note.beamCount() == 2) {
                var prior = owners.get(i - 1);
                if (!OwnedTertiaryBeamlets.provesBackwardHook(
                        ink.pixels, ink.w, ink.h, head.x, head.y, prior.x, prior.y, gap))
                    return null;
            }
            if (dotted && i > 0 && shaftBandAlias(ink, fragment, head, gap, track)) alias = true;
            trial.add(rhythm(head.note, dotted));
        }
        if (!alias) return null;
        for (int i = 0; i < trial.size(); i++) {
            var placement = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 4f).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != i / 2 * .5f + (i % 2 == 1 ? .375f : 0)
                    || placement.durationBeats() != (i % 2 == 0 ? .375f : .125f)) return null;
        }
        return new Proof(fragment, owners);
    }

    /** The putative head is centered on a real stem in a broad, solid beam ribbon. */
    private static boolean shaftBandAlias(
            Ink ink, Head fragment, Head owner, float gap, StaffPitchTrack track) {
        if (owner.x - fragment.x < gap * .35f
                || owner.x - fragment.x > gap * .85f
                || fragment.y - owner.y < gap * 3.5f
                || fragment.y - owner.y > gap * 7.5f) return false;
        for (var shaft :
                PrintedBeamShafts.at(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        owner.x,
                        owner.y,
                        gap,
                        new PrintedBeamShafts.Budget(20_000))) {
            if (shaft.direction() != 1
                    || fragment.x < shaft.left() - gap * .12f
                    || fragment.x > shaft.right() + gap * .12f
                    || shaft.endY() - fragment.y < gap * .3f
                    || shaft.endY() - fragment.y > gap * .9f) continue;
            int middle = Math.round(fragment.x);
            int halfWidth = Math.round(gap * 1.14f);
            int upper = Math.round(fragment.y - gap * .38f);
            int lower = Math.round(fragment.y + gap * .38f);
            int filled = 0, visible = 0;
            for (int y = upper; y <= lower; y++) {
                if (onRule(track.at(middle), y, .17f)) continue;
                visible++;
                int hit = 0;
                for (int x = middle - halfWidth; x <= middle + halfWidth; x++)
                    if (ink.dark(x, y)) hit++;
                if (hit >= 2 * halfWidth) filled++;
            }
            if (visible >= gap * .33f && filled >= visible * .85f) return true;
        }
        return false;
    }

    /** A printed inline meter leaves extra room before the first of three alternating groups. */
    private static Proof inlineMeterHooks(
            Ink ink, MeasureRegion bar, MeasureRegion following, List<Head> row) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !regular(h.note)))
            return null;
        float left = bar.left() * ink.w,
                right = bar.right() * ink.w,
                next = following.left() * ink.w;
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10
                || gap > 27
                || right - left < gap * 29
                || right - left > gap * 39
                || Math.abs(following.top() - bar.top()) * ink.h > gap
                || next - right < gap * .35f
                || next - right > gap * 1.65f) return null;
        var suspects = row.stream().filter(h -> h.note.stemDirection() == 1).toList();
        if (suspects.size() != 1) return null;
        var fragment = suspects.get(0);
        if (fragment.note.beamCount() != 1
                || fragment.note.augmentationDots() != 0
                || fragment.note.articulations() != 0) return null;
        var owners =
                row.stream()
                        .filter(h -> h != fragment)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        var first = owners.get(0);
        var last = owners.get(11);
        var peer = owners.get(10);
        if (first.x - left < gap * 3.65f
                || first.x - left > gap * 5.5f
                || right - last.x < gap * .8f
                || right - last.x > gap * 1.75f
                || fragment.x <= peer.x
                || fragment.x >= last.x) return null;
        var track = readOctaveStaff(ink, left, right, bar.bottom() * ink.h - 2.1f * gap, gap);
        if (track == null) return null;
        gap = track.at(last.x)[1];
        if (!closingStroke(ink, track, right, next, last.x, gap)
                || !OwnedTertiaryBeamlets.ownsTerminalBackwardHookAlias(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        fragment.x,
                        fragment.y,
                        last.x,
                        last.y,
                        peer.x,
                        peer.y,
                        gap)) return null;

        var trial = new ArrayList<ScoreNoteEvent>(owners.size());
        for (int i = 0; i < owners.size(); i++) {
            var own = owners.get(i);
            boolean dotted = i % 2 == 0;
            if (own.note.stemDirection() != -1
                    || own.note.clefBottomDiatonic() != first.note.clefBottomDiatonic()
                    || own.note.augmentationDots() != (dotted ? 1 : 0)
                    || own.note.beamCount() < 2
                    || dotted && own.note.beamCount() != 2) return null;
            if (i > 0
                    && (own.x - owners.get(i - 1).x < gap * 1.5f
                            || own.x - owners.get(i - 1).x > gap * 4.2f)) return null;
            if (!dotted && own != last && own.note.beamCount() == 2) {
                var leftHead = owners.get(i - 1);
                if (!OwnedTertiaryBeamlets.provesBackwardHook(
                        ink.pixels, ink.w, ink.h, own.x, own.y, leftHead.x, leftHead.y, gap))
                    return null;
            }
            trial.add(rhythm(own.note, dotted));
        }
        for (int i = 0; i < trial.size(); i++) {
            boolean dotted = i % 2 == 0;
            var placement = ScoreNoteTiming.writtenPlacement(trial.get(i), trial, 3f).orElse(null);
            if (placement == null
                    || placement.onsetBeats() != i / 2 * .5 + (dotted ? 0 : .375)
                    || placement.durationBeats() != (dotted ? .375 : .125)) return null;
        }
        return new Proof(fragment, owners);
    }

    private static Head backwardHookAlias(Ink ink, MeasureRegion bar, List<Head> row) {
        if (bar.cadenceQuarterBeats() != 0 || row.stream().anyMatch(h -> !regular(h.note)))
            return null;
        float left = bar.left() * ink.w, right = bar.right() * ink.w;
        float gap = (bar.bottom() - bar.top()) * ink.h / 8.2f;
        if (gap < 10 || gap > 27 || right - left < 28 * gap || right - left > 39 * gap) return null;

        var suspects =
                row.stream()
                        .filter(
                                h ->
                                        h.note.beamCount() == 1
                                                && h.note.augmentationDots() == 0
                                                && h.note.stemDirection() == 1)
                        .toList();
        if (suspects.size() != 1) return null;
        var suspect = suspects.get(0);
        if (suspect.note.articulations() != 0) return null;
        var owners =
                row.stream()
                        .filter(h -> h != suspect)
                        .sorted(Comparator.comparingDouble(Head::x))
                        .toList();
        if (owners.get(0).x - left > gap * 2
                || right - owners.get(owners.size() - 1).x > gap * 2.6f) return null;
        var trial = owners.stream().map(Head::note).toList();
        int hooked = -1;
        for (int i = 0; i < owners.size(); i++) {
            var head = owners.get(i);
            boolean dotted = i % 2 == 0;
            if (head.note.beamCount() != (dotted ? 2 : 3)
                    || head.note.augmentationDots() != (dotted ? 1 : 0)
                    || head.note.clefBottomDiatonic() != suspect.note.clefBottomDiatonic()
                    || head.note.stemDirection() == 0
                    || !dotted
                            && head.note.stemDirection() != owners.get(i - 1).note.stemDirection())
                return null;
            if (i != 0
                    && (head.x - owners.get(i - 1).x < gap * 1.5f
                            || head.x - owners.get(i - 1).x > gap * 4.4f)) return null;
            if (!dotted && owners.get(i - 1).x < suspect.x && suspect.x < head.x) hooked = i;
            var timing = ScoreNoteTiming.writtenPlacement(head.note, trial, 3).orElse(null);
            if (timing == null
                    || timing.onsetBeats() != i / 2 * .5 + (dotted ? 0 : .375)
                    || timing.durationBeats() != (dotted ? .375 : .125)) return null;
        }
        if (hooked < 0) return null;

        var terminal = owners.get(owners.size() - 1);
        float sample = right - gap * 2.4f;
        float hint = terminal.y + terminal.note.staffStep() * gap * .5f;
        var rules = localRules(ink, sample, hint, gap);
        if (rules == null || !barline(ink, right, rules)) return null;
        var owner = owners.get(hooked);
        var peer = owners.get(hooked - 1);
        return OwnedTertiaryBeamlets.ownsBackwardHookAlias(
                        ink.pixels,
                        ink.w,
                        ink.h,
                        suspect.x,
                        suspect.y,
                        owner.x,
                        owner.y,
                        peer.x,
                        peer.y,
                        rules[1])
                ? suspect
                : null;
    }

    private static boolean regular(ScoreNoteEvent n) {
        return regular(n, 1);
    }

    private static boolean regular(ScoreNoteEvent n, int maxDots) {
        return regular(n, maxDots, false);
    }

    private static boolean regular(ScoreNoteEvent n, int maxDots, boolean allowInternalTie) {
        return n.unbeamedDurationBeats() == 0 && n.beamCount() > 0 && regularMarkings(n, maxDots, allowInternalTie);
    }

    private static boolean regularMarkings(ScoreNoteEvent n, int maxDots) {
        return regularMarkings(n, maxDots, false);
    }

    private static boolean regularMarkings(ScoreNoteEvent n, int maxDots, boolean allowInternalTie) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0
                && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && !n.crossStaffBeam()
                && n.tupletDivisor() == 1
                && n.beamCount() <= 3
                && n.augmentationDots() <= maxDots
                && (allowInternalTie || !n.tiedFromPrevious())
                && n.boundaryTies() == 0
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && n.harmonicSemitones() == 0
                && n.openStringTouch() == null
                && n.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    static float[] localDottedPairStaff(
            byte[] ink, int width, int height, float x, float floor, float gap) {
        return localRules(new Ink(ink, width, height), x, floor, gap);
    }

    private static float[] localRules(Ink ink,float x,float floor,float gap) {
        int center=Math.round(x), measure=Math.round(gap*2.7f);
        int flank=Math.round(gap*.27f), range=Math.round(gap*.32f);
        int[] rows=new int[5];
        for (int level=0;level<5;level++) {
            int expected=Math.round(floor-level*gap);
            int best=0;
            for(int y=expected-range;y<=expected+range;y++) {
                int hit=0;
                for (int px=center-measure;px<=center+measure;px++)
                    if (ink.dark(px,y) && !ink.dark(px,y-flank) && !ink.dark(px,y+flank)) hit++;
                if (hit>best) {best=hit; rows[level]=y;}
            }
            if (best<measure*.53f) return null;
            if (level>0 && Math.abs(rows[level-1]-rows[level]-gap)>gap*.3f) return null;
        }
        float exactGap=(rows[0]-rows[4])/4f;
        if(exactGap<gap*.85f || exactGap>gap*1.15f) return null;
        return new float[]{rows[0],exactGap};
    }

    private static boolean barline(Ink p,float x,float[] r) {
        if(r[1]<10 || r[1]>27 || r[0]-4*r[1]<1 || r[0]>=p.h) return false;
        var staff=new PlayingTechniqueDetector.Staff(r[0]-4*r[1],r[0],r[1],0,1);
        return PrintedCadenceDurations.boundaryColumn(p.pixels,p.w,staff,x,true,false)>=0;
    }

    private static List<Shaft> shafts(Ink p,Head h,float gap) {
        int first=Math.round(h.y+gap*.2f),limit=first+Math.round(gap*8.05f);
        var out=new ArrayList<Shaft>();
        for(int x=Math.round(h.x-gap*.81f);x<=Math.round(h.x-gap*.41f);x++) {
            int hit=0,last=first-1;
            for(int y=first;y<limit;y++) {
                if(p.dark(x,y)) {last=y;hit++;}
                if(y-last>Math.round(gap*.33f)) break;
            }
            int span=last-first+1;
            if(span>=gap*2 && hit>=span*.88f && last<limit-2)
                out.add(new Shaft(x,last));
        }
        return out;
    }

    private static boolean dot(Ink p,Head a,float next,float gap) {
        return WrappedTripletDotInk.uniqueCore(p.pixels,p.w,p.h,a.x,a.y,next,gap)!=null
                || LegacyTripletDotInk.singleDotCore(p.pixels,p.w,p.h,a.x,a.y,next,gap);
    }

    private static boolean gapBetween(Ink p,Head a,Head b,List<Shaft> left,List<Shaft> right,
            float g,StaffPitchTrack track) {
        int x=Math.round((a.x+b.x)*.5f-g*.57f);
        int tail=Math.max(left.stream().mapToInt(Shaft::tip).max().orElseThrow(),
                right.stream().mapToInt(Shaft::tip).max().orElseThrow());
        int dark=0,pts=0;
        for(int col=x-2;col<=x+2;col++) {
            var r=track.at(col);
            for(int y=tail-Math.round(g*2.25f);y<=tail+2;y++) {
                if(onRule(r,y,.19f)) continue;
                pts++;
                if(p.dark(col,y)) dark++;
            }
        }
        return pts>30 && dark<=pts*.13f;
    }

    private static boolean ownedLink(Ink p,List<Shaft> left,List<Shaft> right,int slot,
            float g,StaffPitchTrack track,Head fragment) {
        return ownedLink(p, left, right, slot, g, track, fragment, false);
    }

    private static boolean ownedLink(
            Ink p,
            List<Shaft> left,
            List<Shaft> right,
            int slot,
            float g,
            StaffPitchTrack track,
            Head fragment,
            boolean boundedThirdRail) {
        int radius=Math.max(2,Math.round(g*.12f)),margin=Math.round(g*.29f);
        for(var a:left) for(var b:right) {
            if(Math.abs(a.tip-b.tip)>g*.6f || b.x-a.x<g*1.35f) continue;
            int first=a.x+margin,last=b.x-margin;
            for(int inset=Math.round(g*.26f);inset<=Math.round(g*.58f);inset++)
                for(int separation=Math.round(g*.65f);separation<=Math.round(g*.96f);separation++) {
                    var f=new Beam(a,b,inset,separation);
                    if (coverage(p,f,first,last,0,radius)<.94
                            || coverage(p,f,first,last,separation,radius)<.94
                            || coverage(p,f,first,last,separation/2,1)>.93) continue;
                    if (slot%2==0 && !hook(p,f,g,radius,track)) continue;
                        if (boundedThirdRail
                                && continuingThirdRail(p, f, first, last, g, radius, track))
                            continue;
                        if (fragment != null
                                && !beamFragment(p, fragment, f, g, track, boundedThirdRail))
                            continue;
                    return true;
                }
        }
        return false;
    }

    static boolean ownsDottedPairAlias(
            byte[] gray,
            int width,
            int height,
            StaffPitchTrack track,
            float gap,
            float dottedX,
            float dottedY,
            float hookX,
            float hookY,
            float aliasX,
            float aliasY) {
        var ink = new Ink(gray, width, height);
        var dotted = new Head(-1, null, dottedX, dottedY);
        var hook = new Head(-1, null, hookX, hookY);
        var alias = new Head(-1, null, aliasX, aliasY);
        return ownedLink(
                ink, shafts(ink, dotted, gap), shafts(ink, hook, gap), 0, gap, track, alias);
    }

    private static boolean continuingThirdRail(
            Ink ink, Beam beam, int first, int last, float gap, int radius, StaffPitchTrack track) {
        for (int shift = Math.round(gap * .53f); shift <= Math.round(gap * 1.12f); shift++) {
            int visible = 0, dark = 0;
            for (int x = first; x <= last; x++) {
                int y = Math.round(beam.outer(x)) - beam.separation - shift;
                if (onRule(track.at(x), y, .18f)) continue;
                visible++;
                if (ink.weight(x, y, radius) >= 2 * radius) dark++;
            }
            if (visible >= gap * .8f && dark >= visible * .86f) return true;
        }
        return false;
    }

    private static float coverage(Ink p,Beam f,int first,int last,int shift,int radius) {
        int count=0;
        for(int x=first;x<=last;x++)
            if(p.weight(x,Math.round(f.outer(x))-shift,radius)>=2*radius) count++;
        return count/(float)(last-first+1);
    }

    private static boolean hook(Ink p,Beam f,float g,int radius,StaffPitchTrack track) {
        int shaft=f.b.x,len=Math.round(g*.66f),outside=Math.round(g*1.1f);
        for(int step=Math.round(g*.53f);step<=Math.round(g*1.12f);step++) {
            int base=Math.round(f.outer(shaft))-f.separation-step;
            int hit=0;
            for(int x=shaft-len;x<=shaft;x++) {
                int y=Math.round(base+(f.outer(x)-f.outer(shaft)));
                if(p.weight(x,y,radius)>=2*radius) hit++;
            }
            if(hit<len || p.weight(shaft,base,1)<2) continue;
            int flank=0;
            for(int dx=outside;dx<=outside+Math.round(g*.36f);dx++) {
                int x=shaft-dx;
                int y=Math.round(base+(f.outer(x)-f.outer(shaft)));
                if(p.weight(x,y,radius)>=2*radius && !onRule(track.at(x),y,.14f)) flank++;
            }
            if(flank<=1 && p.weight(shaft+Math.round(g*.35f),base,radius)<2*radius) return true;
        }
        return false;
    }

    private static boolean beamFragment(
            Ink p, Head h, Beam f, float g, StaffPitchTrack track, boolean octaveRibbon) {
        float maxDepth = octaveRibbon ? 1.55f : 1.12f;
        if (h.x - f.a.x < g * .47f
                || h.x - f.a.x > g * maxDepth
                || h.y < f.outer(h.x) - f.separation - g * .45f
                || h.y > f.outer(h.x) + g * .35f) return false;
        int exterior=0,core=0;
        int left=Math.round(h.x-g*.68f),right=Math.round(h.x+g*.64f);
        for(int x=left;x<=right;x++) {
            var lines=track.at(x);
            for(int y=Math.round(h.y-g*.91f);y<=Math.round(h.y+g*.97f);y++) {
                if(!p.dark(x,y) || onRule(lines,y,.14f)) continue;
                float distance=Math.min(Math.abs(y-f.outer(x)),Math.abs(y-f.outer(x)+f.separation));
                if(distance<=g*.36f) core++;
                else exterior++;
            }
        }
        return core>=g*g*.55f && exterior<=g*.52f;
    }

    private static boolean onRule(float[] rules,float y,float tolerance) {
        float level=(rules[0]-y)/rules[1];
        return level>=-.15f && level<=4.15f && Math.abs(level-Math.round(level))<tolerance;
    }

    private static ScoreNoteEvent rhythm(ScoreNoteEvent n,boolean dotted) {
        if(n.augmentationDots()==(dotted?1:0) && n.beamCount()==(dotted?2:3)) return n;
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                dotted ? 1 : 0,
                dotted ? 2 : 3,
                n.writtenAccidental(),
                0,
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
}
