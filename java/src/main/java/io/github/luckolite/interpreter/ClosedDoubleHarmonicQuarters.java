// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0

package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Reads a closed 2/4 run with two stopped heads and matching touch diamonds on each shaft. */
final class ClosedDoubleHarmonicQuarters {
    private ClosedDoubleHarmonicQuarters() {}

    private record Owner(int index, ScoreNoteEvent note, float x, float y) {}

    private record Attack(Owner upper, Owner lower, int stem, int end, int direction) {}

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        return apply(score, gray, null, width, height);
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score, byte[] gray, byte[] original, int width, int height) {
        var contracted = pairedAttacks(score, gray, original, width, height);
        var opening = openingHarmonicOffsets(score, gray, original, width, height);
        if (contracted.isEmpty() && opening.isEmpty()) return score;
        var renewed = new ArrayList<>(score.notes());
        for (var attack : contracted) {
            for (var owner : List.of(attack.upper(), attack.lower())) {
                var n = owner.note();
                if (!quarter(n) || n.stemDirection() == 0)
                    renewed.set(owner.index(), withQuarter(n, attack.direction()));
            }
        }
        for (int owner : opening.keySet()) {
            var note = score.notes().get(owner);
            if (!quarter(note)) renewed.set(owner, withQuarter(note, note.stemDirection()));
        }
        return renewed.equals(score.notes())
                ? score
                : score.withNotes(renewed, IntStream.range(0, renewed.size()).boxed().toList());
    }

    /** Proves the short final quarter from both stopped heads and their touch diamonds. */
    static ScorePageInterpretation closeTerminalPair(
            ScorePageInterpretation score, byte[] normalized, byte[] original, int width, int height) {
        if (width <= 0 || height <= 0 || original == null || normalized == null
                || original.length != (long) width * height || normalized.length != original.length
                || score.measures().size() < 3 || score.meterChanges().isEmpty()) return score;
        var bars = score.measures();
        int last = bars.size() - 1;
        var meters = score.meterChanges();
        var meter = meters.get(meters.size() - 1);
        var bar = bars.get(last);
        var previous = bars.get(last - 1);
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float span = (bar.right() - bar.left()) * width;
        if (meter.numerator() != 2 || meter.denominator() != 4 || meter.measureIndex() >= last
                || bar.cadenceQuarterBeats() != 0 || gap < 10 || gap > 25
                || span < gap * 5.4f || span > gap * 7.5f
                || Math.abs(bar.top() - previous.top()) * height > gap * .34f
                || score.rests().stream().anyMatch(r -> r.measureIndex() == last)) return score;
        var heads = local(score, last, width, height);
        if (heads.size() != 2 || heads.stream().noneMatch(o -> o.note().beamCount() != 0)
                || heads.stream().anyMatch(o -> !terminalAlias(o.note()))) return score;
        var upper = heads.get(0).y() < heads.get(1).y() ? heads.get(0) : heads.get(1);
        var lower = upper == heads.get(0) ? heads.get(1) : heads.get(0);
        if (lower.note().boundaryTies() != 0
                || upper.note().boundaryTies() != 0 && upper.note().boundaryTies() != 8) return score;
        var untied = new Owner(upper.index(), upper.note().withBoundaryTies(0), upper.x(), upper.y());
        var pair = List.of(untied, lower);
        int a = terminalStem(normalized, width, height, previous, bar, pair, gap);
        int b = terminalStem(original, width, height, previous, bar, pair, gap);
        if (a < 0 || b < 0 || Math.abs(a - b) > gap * .12f) return score;
        var notes = new ArrayList<>(score.notes());
        Integer key = null;
        for (var change : score.keyChanges()) {
            if (change.measureIndex() > last) break;
            key = change.fifths();
        }
        boolean fourths = key != null && printedFourth(upper.note(), key) && printedFourth(lower.note(), key);
        for (var owner : pair) {
            var quarter = withQuarter(owner.note(), -1);
            notes.set(owner.index(), fourths ? quarter.withOctaveShift(0).withHarmonicSemitones(24) : quarter);
        }
        var revised = score.withNotes(notes, IntStream.range(0, notes.size()).boxed().toList());
        var regions = new ArrayList<>(bars);
        regions.set(last, new MeasureRegion(bar.left(), bar.right(), bar.top(), bar.bottom(), 1));
        return new ScorePageInterpretation(
                regions, revised.notes(), revised.firstMeasureNumber(), revised.keyChanges(),
                revised.tempoChanges(), revised.meterChanges(), revised.rests(), revised.techniqueChanges(),
                revised.dynamicChanges(), revised.playbackDirections(), revised.expressiveEvents(),
                revised.slurSource(), revised.openingPickup(), revised.tremoloSource(),
                revised.multiRestGroups(), revised.beamSource(), score.clockContextSource());
    }

    private static boolean printedFourth(ScoreNoteEvent note, int fifths) {
        int diatonic = note.diatonicPitchIdentity();
        int stopped = ScoreTiePitchGuard.writtenMidi(diatonic, note.writtenAccidental(), fifths);
        int touch = ScoreTiePitchGuard.writtenMidi(diatonic + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, fifths);
        return stopped >= 0 && stopped + 24 <= 127 && touch - stopped == 5;
    }

    private static boolean terminalAlias(ScoreNoteEvent note) {
        return note.octaveShift() == 2 && note.harmonicSemitones() == 0
                && note.openStringTouch() == null && note.stemDirection() == -1
                && note.articulations() == 0 && !note.compactOpening()
                && note.clefBottomDiatonic() != ScoreNoteEvent.CLEF_UNKNOWN
                && (quarter(note) || note.beamCount() > 0 && note.unbeamedDurationBeats() == 0);
    }

    private static int terminalStem(
            byte[] gray, int width, int height, MeasureRegion previous, MeasureRegion bar,
            List<Owner> pair, float nominal) {
        float left = bar.left() * width;
        float right = bar.right() * width;
        var fit = PrintedDirectionStaff.printedRules(gray, width, height, right - 3.4f * nominal,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .12f) return -1;
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        int open = PrintedCadenceDurations.boundaryColumn(gray, width, staff,
                (previous.right() * width + left) * .5f, true, false);
        int end = PrintedCadenceDurations.boundaryColumn(gray, width, staff, right, false, true);
        var upper = pair.get(0);
        var lower = pair.get(1);
        if (open < 0 || end < 0 || upper.x() - open < gap * 2f || upper.x() - open > gap * 3.4f
                || end - lower.x() < gap * 3.2f || end - lower.x() > gap * 5.15f
                || !PrintedCadenceDurations.emptyOutsideChord(
                        gray, width, staff, open, end, upper.x(), lower.x(), true)
                || !clearDeparture(gray, width, height, staff, upper, end)) return -1;
        var printed = attack(pair, 0, gray, width, height, staff);
        return printed != null && printed.direction() == -1
                        && ArtificialHarmonics.shaftCrossedDiamond(gray, width, height, lower.x(),
                                lower.y() - gap * 1.5f, gap, printed.stem(), -1, true)
                ? printed.stem() : -1;
    }

    /** An ordinary tie or slur leaves ink between the head and the closing rules. */
    private static boolean clearDeparture(byte[] gray, int width, int height,
            PlayingTechniqueDetector.Staff staff, Owner head, int end) {
        float gap = staff.gap();
        int left = Math.round(head.x() + 1.12f * gap);
        int right = Math.round(end - .55f * gap);
        int top = Math.round(head.y() - 1.30f * gap);
        int bottom = Math.round(head.y() + 1.30f * gap);
        if (left < 0 || right >= width || right - left < gap * 1.35f || top < 0 || bottom >= height)
            return false;
        for (int x = left; x <= right; x++) {
            for (int y = top; y <= bottom; y++) {
                float rule = (y - staff.top()) / gap;
                int nearest = Math.round(rule);
                if (nearest >= 0 && nearest <= 4 && Math.abs(rule - nearest) <= .21f) continue;
                if ((gray[y * width + x] & 255) < 155) return false;
            }
        }
        return true;
    }

    static ScorePageInterpretation removeOwnedTouchHalves(
            ScorePageInterpretation score, byte[] normalized, byte[] original, int width, int height) {
        if (normalized == null || original == null || normalized.length != (long) width * height
                || original.length != normalized.length || score.meterChanges().isEmpty()) return score;
        var meters = score.meterChanges();
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return score;
        var bars = score.measures();
        var updates = new HashMap<Integer, ScoreNoteEvent>();
        var removed = new java.util.HashSet<Integer>();
        for (int i = meter.measureIndex() + 1; i + 1 < bars.size(); i++) {
            var bar = bars.get(i);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0 || nominal < 10 || nominal > 26
                    || span < nominal * 6.4f || span > nominal * 8.15f
                    || Math.abs(bar.top() - bars.get(i - 1).top()) * height > nominal * .35f
                    || Math.abs(bar.top() - bars.get(i + 1).top()) * height > nominal * .35f) continue;
            final int index = i;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var notes = local(score, i, width, height);
            if (notes.size() != 6 || notes.get(2).x() - notes.get(0).x() > nominal * .18f
                    || notes.get(5).x() - notes.get(3).x() > nominal * .18f
                    || notes.get(3).x() - notes.get(2).x() < nominal * 3.0f
                    || notes.get(3).x() - notes.get(2).x() > nominal * 3.85f
                    || notes.subList(0, 3).stream().anyMatch(o -> !ordinary(o.note()) || !quarter(o.note())))
                continue;
            var later = notes.subList(3, 6).stream().sorted(Comparator.comparingDouble(Owner::y)).toList();
            var upper = later.get(0);
            var touch = later.get(1);
            var lower = later.get(2);
            var head = touch.note();
            if (!quarter(upper.note()) || !quarter(lower.note())
                    || upper.note().stemDirection() != -1 || lower.note().stemDirection() != -1
                    || upper.note().octaveShift() != 2 || lower.note().octaveShift() != 0
                    || upper.note().harmonicSemitones() != 0 || lower.note().harmonicSemitones() != 0
                    || head.beamCount() != 0 || head.unbeamedDurationBeats() != 2
                    || head.stemDirection() != -1 || head.articulations() != 0
                    || head.writtenAccidental() != ScoreNoteEvent.ACCIDENTAL_FROM_KEY
                    || head.harmonicSemitones() != 0 || head.octaveShift() != 0
                    || touch.note().staffStep() - lower.note().staffStep() != 3
                    || hasNoteSourceControl(score, touch.index())
                    || !ordinary(withQuarter(head, -1))) continue;
            int a = ownedTouchHalf(normalized, width, height, bars, i, upper, touch, lower, nominal);
            int b = ownedTouchHalf(original, width, height, bars, i, upper, touch, lower, nominal);
            if (a < 0 || b < 0 || Math.abs(a - b) > nominal * .12f) continue;
            removed.add(touch.index());
            updates.put(upper.index(), upper.note().withOctaveShift(0).withHarmonicSemitones(24));
            updates.put(lower.index(), lower.note().withHarmonicSemitones(24));
        }
        if (removed.isEmpty()) return score;
        var owners = IntStream.range(0, score.notes().size()).filter(i -> !removed.contains(i)).boxed().toList();
        return score.withNotes(owners.stream().map(i -> updates.getOrDefault(i, score.notes().get(i))).toList(),
                owners);
    }

    private static boolean hasNoteSourceControl(ScorePageInterpretation score, int index) {
        var origin = score.beamSource().noteOrigins().get(index);
        return origin.isPresent() && (score.beamSource().groups().stream().flatMap(g -> g.columns().stream())
                .anyMatch(c -> c.members().contains(origin.get()))
                || score.beamSource().detachedColumns().stream().anyMatch(c -> c.members().contains(origin.get())));
    }

    /** The half is a diamond on the quarter's established shaft and has no separate stem. */
    private static int ownedTouchHalf(
            byte[] gray, int width, int height, List<MeasureRegion> bars, int index,
            Owner upper, Owner touch, Owner lower, float nominal) {
        var bar = bars.get(index);
        var fit = PrintedDirectionStaff.printedRules(
                gray, width, height, (bar.left() + bar.right()) * width * .5f,
                bar.bottom() * height - 2.1f * nominal, nominal);
        if (fit == null) return -1;
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        if (!interiorRule(gray, width, height, staff, bars.get(index - 1), bar)
                || !interiorRule(gray, width, height, staff, bar, bars.get(index + 1))) return -1;
        var printed = attack(List.of(upper, lower), 0, gray, width, height, staff);
        if (printed == null || printed.direction() != -1
                || Math.abs(lower.y() - touch.y() - 1.5f * gap) > .30f * gap
                || !ArtificialHarmonics.shaftCrossedDiamond(
                        gray, width, height, touch.x(), touch.y(), gap, printed.stem(), -1, true)) return -1;
        var shafts = PrintedBeamShafts.at(gray, width, height, touch.x(), touch.y(), gap,
                new PrintedBeamShafts.Budget(48_000));
        if (shafts.size() != 1) return -1;
        var shaft = shafts.get(0);
        return shaft.direction() == 1 && shaft.left() <= printed.stem() && shaft.right() >= printed.stem()
                ? printed.stem() : -1;
    }

    static Map<Integer, Integer> writtenOnsets(
            ScorePageInterpretation score, byte[] gray, int width, int height, float[] beats) {
        return writtenOnsets(score, gray, null, width, height, beats);
    }

    static Map<Integer, Integer> writtenOnsets(
            ScorePageInterpretation score, byte[] gray, byte[] original,
            int width, int height, float[] beats) {
        var attacks = pairedAttacks(score, gray, original, width, height);
        var placed = new HashMap<Integer, Integer>();
        openingHarmonicOffsets(score, gray, original, width, height).forEach((index, beat) -> {
            if (beats[score.notes().get(index).measureIndex()] == 2) placed.put(index, beat);
        });
        for (int i = 0; i < attacks.size(); i += 2) {
            int bar = attacks.get(i).upper().note().measureIndex();
            if (beats[bar] != 2) continue;
            for (int j = 0; j < 2; j++) {
                var attack = attacks.get(i + j);
                placed.put(attack.upper().index(), j);
                placed.put(attack.lower().index(), j);
            }
        }
        return placed;
    }

    private static Map<Integer, Integer> openingHarmonicOffsets(
            ScorePageInterpretation score, byte[] normalized, byte[] raw, int w, int h) {
        if (normalized == null || raw == null || normalized.length != (long) w * h
                || raw.length != normalized.length || score.meterChanges().isEmpty()) return Map.of();
        var meter = score.meterChanges().get(score.meterChanges().size() - 1);
        int index = meter.measureIndex();
        var bars = score.measures();
        if (meter.numerator() != 2 || meter.denominator() != 4 || index < 1 || index + 1 >= bars.size()
                || score.rests().stream().anyMatch(r -> r.measureIndex() == index)) return Map.of();
        var previous = bars.get(index - 1);
        var opening = bars.get(index);
        var after = bars.get(index + 1);
        float gap = (opening.bottom() - opening.top()) * h / 8.2f;
        float span = (opening.right() - opening.left()) * w;
        if (gap < 10 || gap > 25 || span < gap * 10.2f || span > gap * 13.5f
                || opening.cadenceQuarterBeats() != 0
                || opening.top() - previous.bottom() < gap * 2 / h
                || Math.abs(opening.top() - after.top()) * h > gap * .3f) return Map.of();
        var owners = local(score, index, w, h);
        if (owners.size() != 3 || owners.stream().anyMatch(o -> !ordinary(o.note())))
            return Map.of();
        var a = owners.get(0);
        var b = owners.get(1);
        var upper = a.y() < b.y() ? a : b;
        var lower = a == upper ? b : a;
        var next = owners.get(2);
        if (upper.note().stemDirection() != 1 || lower.note().stemDirection() != 1
                || next.note().stemDirection() != -1 || !quarter(upper.note()) || !quarter(lower.note())
                || !quarter(next.note()) && (next.note().beamCount() != 1
                        || next.note().unbeamedDurationBeats() != 0)
                || upper.note().staffStep() - lower.note().staffStep() != 4
                || next.note().staffStep() - upper.note().staffStep() != 2
                || lower.note().staffStep() >= 0
                || Math.abs(upper.x() - lower.x()) > gap * .16f
                || next.x() - upper.x() < gap * 3.6f || next.x() - upper.x() > gap * 4.75f)
            return Map.of();
        if (!openingHarmonicQuarters(normalized, w, h, opening, after, upper, lower, next, gap)
                || !openingHarmonicQuarters(raw, w, h, opening, after, upper, lower, next, gap))
            return Map.of();
        return Map.of(upper.index(), 0, lower.index(), 0, next.index(), 1);
    }

    private static boolean openingHarmonicQuarters(byte[] gray, int w, int h, MeasureRegion bar,
            MeasureRegion nextBar, Owner upper, Owner lower, Owner next, float nominal) {
        float left = bar.left() * w;
        var fit = PrintedDirectionStaff.printedRules(gray, w, h, (bar.left() + bar.right()) * w * .5f,
                bar.bottom() * h - nominal * 2.1f, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .10f) return false;
        float g = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - g * 4, fit[0], g, 0, 1);
        int right = Math.round(upper.x() - g * 1.22f);
        int top = Math.round(staff.top());
        if (right - left < g * 2.8f || right - left > g * 3.6f
                || !interiorRule(gray, w, h, staff, bar, nextBar)
                || !filledStaffHead(gray, w, h, upper, staff)
                || !filledStaffHead(gray, w, h, lower, staff)
                || !filledStaffHead(gray, w, h, next, staff)
                || !PrintedCadenceDurations.emptyOutsideChord(
                        gray, w, staff, right, bar.right() * w, upper.x(), next.x(), true)) return false;
        var reading = MeterFontMatcher.readCrop(gray, w, h,
                new MeterChangeDetector.Crop(Math.round(left), Math.round(top - g * .32f),
                        right, Math.round(staff.bottom() + g * .52f), top, g));
        if (!reading.text().equals("2/4") || reading.topScore() < .86f || reading.bottomScore() < .86f)
            return false;
        var budget = new PrintedBeamShafts.Budget(72_000);
        var rising = PrintedBeamShafts.at(gray, w, h, lower.x(), lower.y(), g, budget);
        var falling = PrintedBeamShafts.at(gray, w, h, next.x(), next.y(), g, budget);
        if (budget.refused() || rising.size() != 1 || falling.size() != 1) return false;
        var first = rising.get(0);
        var last = falling.get(0);
        if (first.direction() != -1 || last.direction() != 1
                || staff.top() - first.endY() < g * 1.02f
                || last.endY() - staff.bottom() < g * 1.35f) return false;
        int up = (first.left() + first.right()) / 2, down = (last.left() + last.right()) / 2;
        float upperTouch = upper.y() - g * 1.5f;
        float lowerTouch = lower.y() - g;
        // The lowest diamond on the second chord occupies the next staff rule.
        // Check its narrowing shoulders before measuring the exposed tail below it.
        float extra = staff.bottom() - g;
        if (!darkSpine(gray, w, h, up, first.endY(), Math.round(lower.y()), .95f)
                || !darkSpine(gray, w, h, down, Math.round(next.y() - g * 1.15f), last.endY(), .95f)
                || !ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, upper.x(), upperTouch, g, up, 1, true)
                || !ArtificialHarmonics.shaftCrossedDiamond(gray, w, h, lower.x(), lowerTouch, g, up, 1, true)
                || !ArtificialHarmonics.shaftCrossedDiamond(
                        gray, w, h, next.x(), next.y() - g * 1.5f, g, down, -1, true)
                || !taperedLowerHead(gray, w, h, down, next.x(), extra, g)
                || !ArtificialHarmonics.unflaggedQuarterTip(
                        gray, w, h, up, upperTouch, upper.y(), first.endY(), 1, staff)
                || !ArtificialHarmonics.unflaggedQuarterTip(
                        gray, w, h, down, extra, extra, last.endY(), -1, staff)) return false;
        return paperSeparatesAttacks(gray, w, h,
                new Attack(upper, lower, up, first.endY(), 1),
                new Attack(next, next, down, last.endY(), -1), staff);
    }

    private static boolean taperedLowerHead(
            byte[] gray, int w, int h, int stem, float x, float y, float gap) {
        int left = stem + Math.round(gap * .34f), right = Math.round(x + gap * .83f);
        int top = Math.round(y + gap * .23f), bottom = Math.round(y + gap * .8f);
        if (left < 0 || right >= w || top < 0 || bottom >= h) return false;
        int shoulder = rowInk(gray, w, left, right, top);
        int taper = rowInk(gray, w, left, right, Math.round(y + gap * .52f));
        int tip = rowInk(gray, w, left, right, bottom);
        return shoulder >= gap * .73f && taper >= gap * .24f
                && taper <= shoulder - gap * .18f && tip <= taper - gap * .17f;
    }

    private static int rowInk(byte[] gray, int width, int left, int right, int row) {
        int count = 0;
        for (int x = left; x <= right; x++) if ((gray[row * width + x] & 255) < 155) count++;
        return count;
    }

    private static List<Attack> pairedAttacks(
            ScorePageInterpretation score, byte[] gray, byte[] original, int width, int height) {
        var legacy = provedAttacks(score, gray, width, height);
        var bars = score.measures();
        var meters = score.meterChanges();
        if (gray == null || original == null || gray.length != (long) width * height
                || original.length != gray.length || meters.isEmpty()) return legacy;
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return legacy;
        var attacks = new ArrayList<>(legacy);
        for (int i = meter.measureIndex() + 1; i + 1 < bars.size(); i++) {
            int barIndex = i;
            var before = bars.get(i - 1);
            var bar = bars.get(i);
            var after = bars.get(i + 1);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (nominal < 9 || nominal > 25 || span < nominal * 8.4f || span > nominal * 12.5f
                    || bar.cadenceQuarterBeats() != 0
                    || Math.abs(bar.top() - before.top()) * height > nominal * .36f
                    || Math.abs(bar.top() - after.top()) * height > nominal * .36f
                    || legacy.stream().anyMatch(a -> a.upper().note().measureIndex() == barIndex)
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex)) continue;
            var owners = local(score, i, width, height);
            if (owners.size() != 4 || owners.stream().anyMatch(o ->
                    !ordinary(o.note()) || o.note().stemDirection() != -1
                            || !quarter(o.note()) && o.note().beamCount() == 0)) continue;
            var normalized = denseDownstemPair(gray, width, height, before, bar, after, owners, nominal);
            if (normalized.isEmpty()) continue;
            var source = denseDownstemPair(original, width, height, before, bar, after, owners, nominal);
            if (source.isEmpty()) continue;
            boolean matches = true;
            for (int j = 0; j < normalized.size(); j++) {
                if (Math.abs(normalized.get(j).stem() - source.get(j).stem()) > 1
                        || Math.abs(normalized.get(j).end() - source.get(j).end()) > nominal * .19f) {
                    matches = false;
                    break;
                }
            }
            if (matches) attacks.addAll(normalized);
        }
        return attacks;
    }

    private static List<Attack> denseDownstemPair(
            byte[] gray, int width, int height, MeasureRegion before, MeasureRegion bar,
            MeasureRegion after, List<Owner> owners, float nominal) {
        var fit = PrintedDirectionStaff.printedRules(
                gray, width, height, (bar.left() + bar.right()) * width * .5f,
                bar.bottom() * height - nominal * 2.1f, nominal);
        if (fit == null || Math.abs(fit[1] - nominal) > nominal * .12f) return List.of();
        float gap = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
        if (!interiorRule(gray, width, height, staff, before, bar)
                || !interiorRule(gray, width, height, staff, bar, after)) return List.of();
        var first = denseDownstem(gray, width, height, owners.get(0), owners.get(1), staff);
        var second = denseDownstem(gray, width, height, owners.get(2), owners.get(3), staff);
        if (first == null || second == null) return List.of();
        float spacing = second.upper().x() - first.upper().x();
        return spacing >= gap * 3.2f && spacing <= gap * 6.6f
                        && paperSeparatesAttacks(gray, width, height, first, second, staff)
                ? List.of(first, second) : List.of();
    }

    /**
     * The upper owner's immediate ledger and diamond ink can conceal the shoulder of a real
     * downstem. Find the narrow shaft below the lower owner and follow that same shaft upward.
     */
    private static Attack denseDownstem(
            byte[] gray, int width, int height, Owner a, Owner b, PlayingTechniqueDetector.Staff staff) {
        var upper = a.y() < b.y() ? a : b;
        var lower = a == upper ? b : a;
        float gap = staff.gap();
        if (upper.note().staffStep() < 9 || lower.note().staffStep() < 5
                || upper.note().staffStep() - lower.note().staffStep() != 4
                || Math.abs(upper.x() - lower.x()) > gap * .16f
                || !filledStaffHead(gray, width, height, upper, staff)
                || !filledStaffHead(gray, width, height, lower, staff)) return null;
        var budget = new PrintedBeamShafts.Budget(48_000);
        var lowerShafts = PrintedBeamShafts.at(gray, width, height, lower.x(), lower.y(), gap, budget);
        var upperShafts = PrintedBeamShafts.at(gray, width, height, upper.x(), upper.y(), gap, budget);
        if (budget.refused() || upperShafts.stream().anyMatch(s -> s.direction() < 0)
                || lowerShafts.stream().anyMatch(s ->
                        s.direction() < 0 && s.endY() < upper.y() - gap * 1.2f)) return null;
        for (var shaft : lowerShafts) {
            if (shaft.direction() < 0 || shaft.endY() < staff.bottom() - gap * .2f
                    || shaft.endY() > staff.bottom() + gap || shaft.endY() - lower.y() > gap * 4.2f)
                continue;
            int first = (shaft.left() + shaft.right()) / 2;
            for (int stem = first; stem <= shaft.right(); stem++) {
                int rowsStart = Math.round(upper.y() - gap * .28f);
                int end = shaft.endY();
                if (darkSpine(gray, width, height, stem, rowsStart, end, .97f)
                        && darkSpine(gray, width, height, stem + 1, Math.round(lower.y() + gap * .56f),
                                end - 2, .91f)
                        && ArtificialHarmonics.unflaggedQuarterTip(
                                gray, width, height, stem, lower.y() - gap, lower.y(), end, -1, staff))
                    return new Attack(upper, lower, stem, end, -1);
            }
        }
        return null;
    }

    private static boolean filledStaffHead(
            byte[] gray, int width, int height, Owner owner, PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        float gridY = staff.bottom() - owner.note().staffStep() * gap * .5f;
        if (Math.abs(owner.y() - gridY) > gap * .23f) return false;
        int x = Math.round(owner.x()), y = Math.round(owner.y());
        int rx = Math.round(gap * .28f), ry = Math.round(gap * .16f);
        if (x - rx < 0 || x + rx >= width || y - ry < 0 || y + ry >= height) return false;
        int ink = 0;
        for (int row = y - ry; row <= y + ry; row++)
            for (int col = x - rx; col <= x + rx; col++)
                if ((gray[row * width + col] & 255) < 165) ink++;
        return ink >= (2 * rx + 1) * (2 * ry + 1) * .9f;
    }

    private static List<Attack> provedAttacks(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        var fullRun = closedAttacks(score, gray, width, height);
        if (!fullRun.isEmpty() || gray == null || gray.length != (long) width * height)
            return fullRun;
        var bars = score.measures();
        var meters = score.meterChanges();
        if (meters.isEmpty()) return List.of();
        var meter = meters.get(meters.size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4) return List.of();
        var claimed = new ArrayList<Attack>();
        for (int i = meter.measureIndex() + 1; i + 1 < bars.size(); i++) {
            var bar = bars.get(i);
            var previous = bars.get(i - 1);
            var next = bars.get(i + 1);
            float nominal = (bar.bottom() - bar.top()) * height / 8.2f;
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0 || nominal < 9 || nominal > 25
                    || span < nominal * 6.4f || span > nominal * 8.15f
                    || Math.abs(bar.top() - previous.top()) * height > nominal * .36f
                    || Math.abs(bar.top() - next.top()) * height > nominal * .36f) continue;
            int index = i;
            if (score.rests().stream().anyMatch(rest -> rest.measureIndex() == index)) continue;
            var owners = local(score, i, width, height);
            if (owners.size() != 4 || owners.stream().filter(o -> quarter(o.note())).count() < 3
                    || owners.stream().anyMatch(o -> !quarter(o.note()) && o.note().beamCount() == 0))
                continue;
            var fit = PrintedDirectionStaff.printedRules(
                    gray, width, height, (bar.left() + bar.right()) * width * .5f,
                    bar.bottom() * height - nominal * 2.1f, nominal);
            if (fit == null) continue;
            float gap = fit[1];
            var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * gap, fit[0], gap, 0, 1);
            if (!interiorRule(gray, width, height, staff, previous, bar)
                    || !interiorRule(gray, width, height, staff, bar, next)) continue;
            var first = attack(owners, 0, gray, width, height, staff);
            var second = attack(owners, 2, gray, width, height, staff);
            if (first == null || second == null || first.direction() != second.direction()
                    || second.upper().x() - first.upper().x() < gap * 3.15f
                    || second.upper().x() - first.upper().x() > gap * 3.85f
                    || !paperSeparatesAttacks(gray, width, height, first, second, staff)) continue;
            claimed.add(first);
            claimed.add(second);
        }
        return claimed;
    }

    private static List<Attack> closedAttacks(
            ScorePageInterpretation score, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return List.of();
        var bars = score.measures();
        int end = bars.size() - 1;
        if (end < 3 || bars.get(end).cadenceQuarterBeats() > 1 || score.meterChanges().isEmpty())
            return List.of();
        var meter = score.meterChanges().get(score.meterChanges().size() - 1);
        if (meter.numerator() != 2 || meter.denominator() != 4 || meter.measureIndex() > end - 2)
            return List.of();

        var terminal = bars.get(end);
        float nominal = (terminal.bottom() - terminal.top()) * height / 8.2f;
        if (nominal < 9 || nominal > 25) return List.of();
        var finale = local(score, end, width, height);
        float terminalWidth = (terminal.right() - terminal.left()) * width;
        if (terminalWidth < nominal * 4.3f
                || terminalWidth > nominal * 6.1f
                || finale.size() != 2
                || finale.stream().anyMatch(o -> !quarter(o.note()))
                || score.rests().stream().anyMatch(r -> r.measureIndex() == end)) return List.of();
        var fit =
                PrintedDirectionStaff.printedRules(
                        gray,
                        width,
                        height,
                        finale.get(0).x(),
                        terminal.bottom() * height - nominal * 2.1f,
                        nominal);
        if (fit == null) return List.of();
        float g = fit[1];
        var staff = new PlayingTechniqueDetector.Staff(fit[0] - 4 * g, fit[0], g, 0, 1);
        if (PrintedCadenceDurations.boundaryColumn(
                        gray, width, staff, terminal.right() * width, false, false)
                < 0) return List.of();
        if (attack(finale, 0, gray, width, height, staff) == null) return List.of();

        List<Attack> contracted = new ArrayList<>();
        for (int i = end - 1; i >= meter.measureIndex(); i--) {
            var bar = bars.get(i);
            float span = (bar.right() - bar.left()) * width;
            if (bar.cadenceQuarterBeats() != 0
                    || span < g * 4.65f
                    || span > g * 7.15f
                    || Math.abs(bar.top() - terminal.top()) * height > g * .36f
                    || !interiorRule(gray, width, height, staff, bar, bars.get(i + 1))) break;
            final int barIndex = i;
            var notes = local(score, i, width, height);
            if (notes.size() != 4
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == barIndex)) break;
            var a = attack(notes, 0, gray, width, height, staff);
            var b = attack(notes, 2, gray, width, height, staff);
            if (a == null
                    || b == null
                    || a.direction() != b.direction()
                    || b.upper().x() - a.upper().x() < g * 2.30f
                    || b.upper().x() - a.upper().x() > g * 3.40f
                    || !paperSeparatesAttacks(gray, width, height, a, b, staff)) break;
            contracted.add(a);
            contracted.add(b);
        }
        if (contracted.size() < 4) return List.of();

        return contracted;
    }

    private static List<Owner> local(
            ScorePageInterpretation score, int bar, int width, int height) {
        var bound = score.measures().get(bar);
        var notes = new ArrayList<Owner>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() != bar) continue;
            float x =
                    (bound.left() + note.positionInMeasure() * (bound.right() - bound.left()))
                            * width;
            notes.add(new Owner(i, note, x, note.pageY() * height));
        }
        notes.sort(Comparator.comparingDouble(Owner::x));
        return notes;
    }

    private static boolean quarter(ScoreNoteEvent n) {
        return n.beamCount() == 0 && n.unbeamedDurationBeats() == 1;
    }

    private static boolean ordinary(ScoreNoteEvent n) {
        return n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL
                && n.staffCount() == 1
                && n.staffIndex() == 0
                && n.beamCount() <= 2
                && n.unbeamedDurationBeats() <= 1
                && n.augmentationDots() == 0
                && n.tupletDivisor() == 1
                && n.tupletNormalNotes() == 1
                && n.boundaryTies() == 0
                && !n.tiedFromPrevious()
                && !n.crossStaffBeam()
                && n.leadingRestBeats() == 0
                && n.followingRestBeats() == 0
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static Attack attack(
            List<Owner> notes,
            int offset,
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff) {
        var first = notes.get(offset);
        var second = notes.get(offset + 1);
        var upper = first.y() < second.y() ? first : second;
        var lower = upper == first ? second : first;
        float gap = staff.gap();
        if (!ordinary(upper.note())
                || !ordinary(lower.note())
                || upper.note().staffStep() - lower.note().staffStep() < 4
                || upper.note().staffStep() - lower.note().staffStep() > 5
                || Math.abs(upper.x() - lower.x()) > gap * .16f
                || lower.y() - upper.y() < gap * 1.98f
                || lower.y() - upper.y() > gap * 2.78f) return null;

        var shafts =
                PrintedBeamShafts.at(
                        gray,
                        width,
                        height,
                        upper.x(),
                        upper.y(),
                        gap,
                        new PrintedBeamShafts.Budget(48_000));
        if (shafts.size() != 1) return null;
        var shaft = shafts.get(0);
        int direction = -shaft.direction();
        if (upper.note().stemDirection() != direction
                || lower.note().stemDirection() != 0 && lower.note().stemDirection() != direction)
            return null;

        for (int stem = shaft.left() - (direction < 0 ? 2 : 0); stem <= shaft.right(); stem++) {
            int end =
                    direction < 0
                            ? continuedLowerStemEnd(gray, width, height, stem, lower.y(), gap)
                            : shaft.endY();
            if (end < 0
                    || !ArtificialHarmonics.shaftCrossedDiamond(
                            gray,
                            width,
                            height,
                            upper.x(),
                            upper.y() - gap * 1.5f,
                            gap,
                            stem,
                            direction,
                            true)
                    || !lowerTouch(gray, width, height, lower, stem, direction, gap)
                    || !darkSpine(
                            gray,
                            width,
                            height,
                            stem,
                            Math.round(upper.y()),
                            Math.round(lower.y()),
                            .86f)) continue;
            var tipOwner = direction > 0 ? upper : lower;
            if (ArtificialHarmonics.unflaggedQuarterTip(
                    gray,
                    width,
                    height,
                    stem,
                    tipOwner.y() - gap * 1.5f,
                    tipOwner.y(),
                    end,
                    direction,
                    staff)) return new Attack(upper, lower, stem, end, direction);
        }
        return null;
    }

    private static boolean lowerTouch(
            byte[] gray, int width, int height, Owner note, int stem, int direction, float gap) {
        return ArtificialHarmonics.shaftCrossedDiamond(
                        gray,
                        width,
                        height,
                        note.x(),
                        note.y() - gap * 1.5f,
                        gap,
                        stem,
                        direction,
                        true)
                || ArtificialHarmonics.shaftCrossedDiamond(
                        gray, width, height, note.x(), note.y() - gap, gap, stem, direction, true);
    }

    private static boolean darkSpine(
            byte[] gray, int width, int height, int x, int start, int end, float fraction) {
        if (x < 0 || x >= width || start < 0 || end >= height || end < start) return false;
        int ink = 0;
        for (int y = start; y <= end; y++) if ((gray[y * width + x] & 255) < 180) ink++;
        return ink >= (end - start + 1) * fraction;
    }

    private static int continuedLowerStemEnd(
            byte[] gray, int width, int height, int stem, float stoppedY, float gap) {
        int first = Math.round(stoppedY + gap * .32f), last = Math.round(stoppedY + gap * 2.75f);
        if (stem <= 0 || stem + 1 >= width || first < 0 || last + 1 >= height) return -1;
        int y = first;
        while (y <= last && (gray[y * width + stem] & 255) < 180) y++;
        int end = y - 1;
        if (end - stoppedY < gap * 1.08f || y > last) return -1;
        int lowerStart = Math.round(stoppedY + gap * .58f);
        return darkSpine(gray, width, height, stem - 1, lowerStart, end - 2, .90f)
                        || darkSpine(gray, width, height, stem + 1, lowerStart, end - 2, .90f)
                ? end
                : -1;
    }

    private static boolean paperSeparatesAttacks(
            byte[] gray,
            int width,
            int height,
            Attack a,
            Attack b,
            PlayingTechniqueDetector.Staff staff) {
        float gap = staff.gap();
        int left = a.stem() + Math.round(gap * .88f), right = b.stem() - Math.round(gap * .88f);
        int top = Math.min(a.end(), b.end()) - Math.round(gap * .42f);
        int bottom = Math.max(a.end(), b.end()) + Math.round(gap * .42f);
        if (left < 0 || right >= width || top < 0 || bottom >= height || right - left < gap * .38f)
            return false;
        int clear = 0;
        for (int x = left; x <= right; x++) {
            int ink = 0;
            for (int y = top; y <= bottom; y++) {
                float phase = (staff.bottom() - y) / gap;
                int rule = Math.round(phase);
                if (rule >= 0 && rule <= 4 && Math.abs(phase - rule) <= .16f) continue;
                if ((gray[y * width + x] & 255) < 170) ink++;
            }
            clear = ink > gap * .14f ? 0 : clear + 1;
            if (clear >= gap * .43f) return true;
        }
        return false;
    }

    private static boolean interiorRule(
            byte[] gray,
            int width,
            int height,
            PlayingTechniqueDetector.Staff staff,
            MeasureRegion before,
            MeasureRegion after) {
        float expected = (before.right() + after.left()) * width * .5f;
        return ClosedSixteenthPairBeforeHalf.ownedSeparator(gray, width, height, staff, expected);
    }

    private static ScoreNoteEvent withQuarter(ScoreNoteEvent n, int direction) {
        return new ScoreNoteEvent(
                n.measureIndex(),
                n.positionInMeasure(),
                n.staffStep(),
                n.staffIndex(),
                n.staffCount(),
                n.pageY(),
                n.tiedFromPrevious(),
                n.augmentationDots(),
                0,
                n.writtenAccidental(),
                1,
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
                direction,
                n.kind(),
                n.visualSize(),
                n.harmonicSemitones(),
                n.openStringTouch());
    }
}
