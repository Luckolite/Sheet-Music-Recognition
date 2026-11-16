// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Remove literal rest aliases only when a printed header or two accidental owners prove the ink. */
final class RestCompetingGlyphs {
    private RestCompetingGlyphs() {}

    static List<ScoreRestEvent> remove(List<ScoreRestEvent> rests, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, byte[] labels, byte[] gray, byte[] source, int width, int height) {
        if (rests.isEmpty() || gray == null || source == null || labels.length != gray.length
                || gray.length != source.length || gray.length != (long) width * height) return rests;
        var kept = new ArrayList<ScoreRestEvent>(rests.size());
        List<MeterChangeDetector.Crop> headers = null;
        for (var rest : rests) {
            if (rest.staffCount() != 1 || rest.staffIndex() != 0 || rest.isFullMeasure()) {
                kept.add(rest);
                continue;
            }
            int bar = rest.measureIndex();
            boolean atOpening = bar == 0 || measures.get(bar).top() > measures.get(bar - 1).bottom();
            if (rest.durationBeats() == 1 && atOpening && rest.positionInMeasure() < .14f) {
                if (headers == null) headers = MeterChangeDetector.candidates(labels, gray, width, height);
                if (meterOwns(rest, notes, measures, headers, labels, gray, source, width, height))
                    continue;
            }
            if (rest.durationBeats() == 1
                    && naturalOwnsQuarterAlias(
                            rest, notes, measures.get(bar), gray, source, width, height)) continue;
            if (rest.durationBeats() == .5
                    && stackedFingeringsOwnEighthRest(
                            rest,
                            notes,
                            measures.get(bar),
                            bar + 1 < measures.size() ? measures.get(bar + 1) : null,
                            gray,
                            source,
                            width,
                            height)) continue;
            if (rest.durationBeats() == .125
                    && (accidentalsOwn(rest, notes, measures.get(bar), labels, gray, source, width, height)
                            || lowerNaturalOwns(rest, notes, measures.get(bar), gray, source, width, height)
                            || stackedHarmonicNaturalsOwn(
                                    rest, notes, measures.get(bar), labels, gray, source, width, height)))
                continue;
            kept.add(rest);
        }
        return kept.size() == rests.size() ? rests : List.copyOf(kept);
    }

    private static boolean stackedFingeringsOwnEighthRest(
            ScoreRestEvent rest,
            List<ScoreNoteEvent> notes,
            MeasureRegion bar,
            MeasureRegion next,
            byte[] gray,
            byte[] source,
            int width,
            int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float restSize = rest.pageHeight() * height;
        float rx = x(bar, rest.positionInMeasure(), width), ry = rest.pageY() * height;
        float span = (bar.right() - bar.left()) * width;
        if (rest.kind() != ScoreRestEvent.Kind.LITERAL
                || gap < 10
                || gap > 27
                || span < 3.7f * gap
                || span > 7 * gap
                || Math.abs(ry - bar.top() * height) > gap * .38f
                || restSize < gap * 1.7f
                || restSize > gap * 2.25f) return false;
        var chord = notes.stream().filter(n -> n.measureIndex() == rest.measureIndex()).toList();
        if (chord.size() != 2
                || Math.abs(chord.get(0).staffStep() - chord.get(1).staffStep()) != 7
                || chord.stream()
                        .anyMatch(
                                n ->
                                        n.kind() != ScoreNoteEvent.Kind.PITCHED
                                                || n.staffIndex() != 0
                                                || n.staffCount() != 1
                                                || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                                                || n.unbeamedDurationBeats() != 2
                                                || n.beamCount() != 0
                                                || n.augmentationDots() != 0
                                                || n.tupletDivisor() != 1
                                                || n.tiedFromPrevious()
                                                || n.crossStaffBeam()
                                                || n.boundaryTies() != 0
                                                || n.followingRestBeats() != 0
                                                || (n.articulations() & NoteOrnament.GRACE) != 0
                                                || Math.abs(
                                                                x(bar, n.positionInMeasure(), width)
                                                                        - rx)
                                                        > gap * .33f)) return false;
        float headX =
                (x(bar, chord.get(0).positionInMeasure(), width)
                                + x(bar, chord.get(1).positionInMeasure(), width))
                        * .5f;
        float headY = Math.min(chord.get(0).pageY(), chord.get(1).pageY()) * height;
        for (byte[] raster : List.of(gray, source)) {
            var rails =
                    PrintedDirectionStaff.printedRules(
                            raster, width, height, headX, bar.bottom() * height - 2.1f * gap, gap);
            if (rails == null) return false;
            float measured = rails[1], top = rails[0] - 4 * measured;
            if (top - (ry + restSize * .5f) < measured * .8f
                    || top - (ry + restSize * .5f) > measured * 1.55f
                    || !threeThenOne(raster, width, height, rx, ry, headX, measured)) return false;
            var staff = new PlayingTechniqueDetector.Staff(top, rails[0], measured, 0, 1);
            var stem = PairedTremoloInk.stem(raster, width, height, headX, headY, measured, 1);
            if (stem == null
                    || stem.x() < headX + measured * .45f
                    || stem.endY() < top - measured * 1.3f
                    || !PrintedCadenceDurations.proved(
                            raster, width, height, bar, next, List.of(staff), chord, false))
                return false;
        }
        return true;
    }

    static boolean threeThenOne(
            byte[] gray, int width, int height, float restX, float restY, float headX, float gap) {
        int left = Math.round(restX - gap * .77f);
        int right = Math.round(Math.min(restX + gap * .76f, headX + gap * .47f));
        int top = Math.round(restY - gap * 1.9f), bottom = Math.round(restY + gap * 1.46f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        var glyphs =
                RawNumeralComponents.capture(
                                gray, width, height, left, top, right, bottom, () -> {}, () -> {})
                        .components()
                        .stream()
                        .filter(c -> c.size() >= 8)
                        .sorted(Comparator.comparingInt(c -> c.top))
                        .toList();
        if (glyphs.size() != 2) return false;
        var three = glyphs.get(0);
        var one = glyphs.get(1);
        if (three.top <= top
                || one.bottom >= bottom
                || one.top - three.bottom < gap * .15f
                || one.top - three.bottom > gap * .55f) return false;
        for (var digit : glyphs) {
            float dx = (digit.left + digit.right) * .5f - restX;
            int glyphHeight = digit.bottom - digit.top + 1;
            if (digit.left <= left
                    || digit.right >= right
                    || Math.abs(dx) > gap * .3f
                    || glyphHeight < gap * .85f
                    || glyphHeight > gap * 1.34f) return false;
        }
        return TripletRhythmDetector.isIsolatedThree(three, width)
                && TripletRhythmDetector.fingerOne(gray, width, one);
    }

    private static boolean meterOwns(ScoreRestEvent rest, List<ScoreNoteEvent> notes,
            List<MeasureRegion> measures, List<MeterChangeDetector.Crop> headers,
            byte[] labels, byte[] gray, byte[] source, int width, int height) {
        int bar = rest.measureIndex();
        float x = x(measures.get(bar), rest.positionInMeasure(), width), y = rest.pageY() * height;
        float halfHeight = rest.pageHeight() * height * .5f;
        for (var crop : headers) {
            float middle = (crop.left() + crop.right()) * .5f, gap = crop.gap();
            if (Math.abs(x - middle) > gap * .3f || y-halfHeight < crop.top()
                    || y+halfHeight > crop.bottom()
                    || MeterChangeDetector.followingMeasure(crop, width, height, measures) != bar
                    || !MeterChangeDetector.precedesNotes(crop, labels, gray, width, height, bar, measures, notes))
                continue;
            var a = MeterFontMatcher.readCrop(gray, width, height, crop);
            var b = MeterFontMatcher.readCrop(source, width, height, crop);
            if (!a.text().isBlank() && a.text().equals(b.text())
                    && Math.min(Math.min(a.topScore(), a.bottomScore()),
                            Math.min(b.topScore(), b.bottomScore())) >= .84f) return true;
        }
        return false;
    }

    private static boolean naturalOwnsQuarterAlias(
            ScoreRestEvent rest,
            List<ScoreNoteEvent> notes,
            MeasureRegion region,
            byte[] gray,
            byte[] source,
            int width,
            int height) {
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (gap < 10
                || gap > 25
                || rest.pageHeight() * height < gap * 2.25f
                || rest.pageHeight() * height > gap * 3.05f) return false;
        var local =
                notes.stream()
                        .filter(n -> n.measureIndex() == rest.measureIndex())
                        .sorted(Comparator.comparingDouble(ScoreNoteEvent::positionInMeasure))
                        .toList();
        if (local.size() != 12
                || local.stream()
                        .anyMatch(
                                n ->
                                        n.kind() != ScoreNoteEvent.Kind.PITCHED
                                                || n.staffIndex() != 0
                                                || n.staffCount() != 1
                                                || n.crossStaffBeam()
                                                || n.beamCount() != 1
                                                || n.augmentationDots() != 0
                                                || n.unbeamedDurationBeats() != 0
                                                || (n.articulations() & NoteOrnament.GRACE) != 0))
            return false;
        var before = local.get(0);
        var after = local.get(1);
        if (after.writtenAccidental() != 0) return false;
        float rx = x(region, rest.positionInMeasure(), width), ry = rest.pageY() * height;
        float left = x(region, before.positionInMeasure(), width);
        float right = x(region, after.positionInMeasure(), width);
        float ay = after.pageY() * height;
        if (rx - left < gap * 1.25f
                || rx - left > gap * 1.95f
                || right - rx < gap * 1.20f
                || right - rx > gap * 1.70f
                || Math.abs(ay - ry) > gap * .30f) return false;
        var frame =
                PrintedDirectionStaff.printedRules(
                        gray,
                        width,
                        height,
                        left + gap * 3,
                        region.bottom() * height - gap * 2.1f,
                        gap);
        if (frame == null) return false;
        int staffTop = Math.round(frame[0] - 4 * frame[1]);
        int minX = Math.round(rx - gap * .65f), maxX = Math.round(right - gap * .65f);
        for (var raster : List.of(gray, source)) {
            if (!OmrScoreInterpreter.provesNaturalMeterInk(
                    raster, width, height, minX, maxX, ay, frame[1], staffTop)) return false;
            if (!PrintedTupletBeamOwner.connectedHeads(
                    raster, width, height, left, before.pageY() * height, right, ay, frame[1], -1))
                return false;
        }
        return true;
    }

    private static boolean accidentalsOwn(ScoreRestEvent rest, List<ScoreNoteEvent> notes,
            MeasureRegion region, byte[] labels, byte[] gray, byte[] source, int width, int height) {
        float rx = x(region, rest.positionInMeasure(), width), ry = rest.pageY() * height;
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        if (gap < 6 || gap > 32 || rest.pageHeight()*height < gap * 3.4f
                || rest.pageHeight()*height > gap * 4.9f) return false;
        float neighborhood = gap * 3;
        var row = notes.stream().filter(n -> n.measureIndex() == rest.measureIndex()
                && Math.abs(x(region, n.positionInMeasure(), width)-rx) < neighborhood)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::pageY)).toList();
        var before = row.stream().filter(n->n.positionInMeasure()<rest.positionInMeasure()).toList();
        var after = row.stream().filter(n->n.positionInMeasure()>rest.positionInMeasure()).toList();
        if (before.size() != 2 || after.size() != 2) return false;
        if (row.stream().anyMatch(n->n.staffCount()!=1 || n.staffIndex()!=0
                || n.beamCount()!=2 || n.augmentationDots()!=0
                || n.tupletDivisor()!=1 || n.crossStaffBeam())) return false;
        float ax = x(region,after.get(0).positionInMeasure(),width);
        if (ax-rx < gap*1.2f || ax-rx > gap*2.25f
                || Math.abs(ax-x(region,after.get(1).positionInMeasure(),width))>gap*.25f
                || Math.abs(x(region,before.get(0).positionInMeasure(),width)
                        -x(region,before.get(1).positionInMeasure(),width))>gap*.25f) return false;
        float firstY=after.get(0).pageY()*height, secondY=after.get(1).pageY()*height;
        if (secondY-firstY<gap*2 || secondY-firstY>gap*3.2f || ry<firstY || ry>secondY)
            return false;

        var rails = PrintedDirectionStaff.printedRules(gray,width,height,rx,
                region.bottom()*height-2.1f*gap,gap);
        if (rails==null) return false;
        gap=rails[1];
        int staffTop=Math.round(rails[0]-4*gap);
        if (!naturalPair(labels,gray,width,height,rx,firstY,secondY,gap,staffTop)
                || !naturalPair(labels,source,width,height,rx,firstY,secondY,gap,staffTop))
            return false;
        // Stem directions are not assigned at this stage of raw note ownership.
        // Require sustained beams with a single common optical orientation on both rasters.
        boolean up = joinedByBeams(before,after,region,gray,width,height,gap,1)
                && joinedByBeams(before,after,region,source,width,height,gap,1);
        boolean down = joinedByBeams(before,after,region,gray,width,height,gap,-1)
                && joinedByBeams(before,after,region,source,width,height,gap,-1);
        return up != down;
    }

    private static boolean joinedByBeams(List<ScoreNoteEvent> before,List<ScoreNoteEvent> after,
            MeasureRegion region,byte[] raster,int width,int height,float gap,int direction) {
        for (int i=0;i<2;i++) {
            var a=before.get(i); var b=after.get(i);
            if (a.stemDirection()!=0 && a.stemDirection()!=direction
                    || b.stemDirection()!=0 && b.stemDirection()!=direction
                    || !PrintedTupletBeamOwner.connectedHeads(raster,width,height,
                            x(region,a.positionInMeasure(),width),a.pageY()*height,
                            x(region,b.positionInMeasure(),width),b.pageY()*height,gap,direction))
                return false;
        }
        return true;
    }

    private static boolean lowerNaturalOwns(
            ScoreRestEvent rest, List<ScoreNoteEvent> notes, MeasureRegion region,
            byte[] gray, byte[] source, int width, int height) {
        float gap = (region.bottom() - region.top()) * height / 8.2f;
        float size = rest.pageHeight() * height;
        if (gap < 10 || gap > 27 || size < gap * 3.4f || size > gap * 4.1f) return false;

        float rx = x(region, rest.positionInMeasure(), width);
        var nearby = new ArrayList<ScoreNoteEvent>();
        float left = Float.NEGATIVE_INFINITY;
        float right = Float.POSITIVE_INFINITY;
        for (var note : notes) {
            if (note.measureIndex() != rest.measureIndex()) continue;
            float at = x(region, note.positionInMeasure(), width);
            if (Math.abs(rx - at) > gap * 3.55f) continue;
            if (at < rx) left = Math.max(at, left);
            else right = Math.min(at, right);
            nearby.add(note);
        }
        if (rx - left < gap * 1.4f || rx - left > gap * 3.3f
                || right - rx < gap * 1.4f || right - rx > gap * 2.15f) return false;
        var before = dyadNear(nearby, region, width, left, gap);
        var after = dyadNear(nearby, region, width, right, gap);
        if (before.size() != 2 || after.size() != 2) return false;
        float lowerY = after.get(1).pageY() * height;
        float separation = lowerY - after.get(0).pageY() * height;
        if (separation < gap * 1.75f || separation > gap * 3.25f
                || Math.abs(rest.pageY() * height - (lowerY - gap * .5f)) > gap * .20f) return false;
        if (!ownedLowerNatural(gray, width, height, region, rx, lowerY, gap)
                || !ownedLowerNatural(source, width, height, region, rx, lowerY, gap)) return false;
        // Both columns must be attached to the same printed beams across the glyph.
        boolean up = joinedByBeams(before, after, region, gray, width, height, gap, 1)
                && joinedByBeams(before, after, region, source, width, height, gap, 1);
        boolean down = joinedByBeams(before, after, region, gray, width, height, gap, -1)
                && joinedByBeams(before, after, region, source, width, height, gap, -1);
        return up != down;
    }

    private static List<ScoreNoteEvent> dyadNear(
            List<ScoreNoteEvent> notes, MeasureRegion region, int width, float x, float gap) {
        var heads = notes.stream().filter(n -> Math.abs(x(region, n.positionInMeasure(), width) - x) <= gap * .25f)
                .sorted(Comparator.comparingDouble(ScoreNoteEvent::pageY)).toList();
        if (heads.stream().anyMatch(n -> n.kind() != ScoreNoteEvent.Kind.PITCHED
                || n.staffCount() != 1 || n.staffIndex() != 0 || n.beamCount() != 2
                || n.augmentationDots() != 0 || n.tupletDivisor() != 1 || n.crossStaffBeam()
                || n.visualSize() != ScoreNoteEvent.VisualSize.FULL
                || (n.articulations() & NoteOrnament.GRACE) != 0)) return List.of();
        return heads;
    }

    private static boolean ownedLowerNatural(
            byte[] gray, int width, int height, MeasureRegion region, float x, float noteY, float gap) {
        float hint = region.bottom() * height - 2.1f * gap;
        int step = Math.max(2, Math.round(gap * .25f));
        for (int radius = 0; radius <= gap * 7.5f; radius += step) {
            for (int sign : radius == 0 ? new int[] {1} : new int[] {1, -1}) {
                int axis = Math.round(x) + sign * radius;
                if (axis < region.left() * width || axis > region.right() * width) continue;
                var rails = DottedDyadMeasureSeparator.localRails(gray, width, height, axis, hint, gap);
                if (rails == null) continue;
                float g = rails[1];
                var staff = new PlayingTechniqueDetector.Staff(rails[0] - 4 * g, rails[0], g, 0, 1);
                return ClosedLateTripleDyads.provesLowerNatural(gray, width, height, x, noteY + .12f * g, staff)
                        || ClosedLateTripleDyads.provesLowerNatural(gray, width, height, x - .12f * g, noteY, staff);
            }
        }
        return false;
    }

    private static boolean stackedHarmonicNaturalsOwn(
            ScoreRestEvent rest, List<ScoreNoteEvent> notes, MeasureRegion bar, byte[] labels,
            byte[] gray, byte[] source, int width, int height) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        float size = rest.pageHeight() * height, rx = x(bar, rest.positionInMeasure(), width);
        float ry = rest.pageY() * height;
        if (gap < 10 || gap > 26 || size < gap * 3.3f || size > gap * 4.2f) return false;
        ScoreNoteEvent before = null, after = null;
        for (var n : notes) {
            if (n.measureIndex() != rest.measureIndex()) continue;
            float dx = x(bar, n.positionInMeasure(), width) - rx;
            if (n.pageY() * height < ry) continue;
            if (dx >= -gap * 2.4f && dx <= -gap * 1.25f) {
                if (before != null) return false;
                before = n;
            }
            if (dx >= gap * 1.25f && dx <= gap * 2.1f) {
                if (after != null) return false;
                after = n;
            }
        }
        if (!beamedStoppedHead(before) || !beamedStoppedHead(after)) return false;
        float lx = x(bar, before.positionInMeasure(), width), ly = before.pageY() * height;
        float ax = x(bar, after.positionInMeasure(), width), ay = after.pageY() * height;
        if (Math.abs(ay - ly) > gap * .8f || Math.abs(ry - (ay - gap * .5f)) > gap * .24f)
            return false;
        for (var n : notes) {
            if (n.measureIndex() != rest.measureIndex() || n == before || n == after) continue;
            float nx = x(bar, n.positionInMeasure(), width), ny = n.pageY() * height;
            float dx = nx - rx;
            if (dx >= -gap * 2.4f && dx <= -gap * 1.25f
                    && !sameTouchPosition(nx, ny, lx, ly, gap)) return false;
            if (dx >= gap * 1.25f && dx <= gap * 2.1f
                    && !sameTouchPosition(nx, ny, ax, ay, gap)) return false;
        }
        int left = Math.round(rx - gap * 1.05f), right = Math.round(rx + gap * .8f);
        int top = Math.round(ry - size * .5f - gap * .92f);
        int bottom = Math.round(ry + size * .5f + gap * .37f);
        if (left < 0 || right >= width || top < 0 || bottom >= height) return false;
        int w = right - left + 1, h = bottom - top + 1;
        byte glyph = OmrMeasurePostProcessor.CLEF_OR_KEY;
        var ink = new byte[w * h];
        for (byte[] raster : List.of(gray, source)) {
            for (int row = 0; row < h; row++)
                for (int col = 0; col < w; col++) {
                    int at = (top + row) * width + left + col;
                    ink[row * w + col] = labels[at] == glyph && (raster[at] & 255) < 155 ? glyph : 0;
                }
            var centers = StackedNaturalColumn.centers(ink, w, h, 0, 0, w - 1, h - 1, glyph, gap);
            if (centers.length != 2
                    || Math.abs(top + centers[1] - ay) > gap * .32f
                    || Math.abs(top + centers[0] - (ay - gap * 1.5f)) > gap * .32f
                    || !PrintedTupletBeamOwner.connectedHeads(raster, width, height, lx, ly, ax, ay, gap, -1)
                    || !sameShaftTouch(raster, width, height, lx, ly, gap)
                    || !sameShaftTouch(raster, width, height, ax, ay, gap)) return false;
        }
        return true;
    }

    private static boolean beamedStoppedHead(ScoreNoteEvent n) {
        return n != null && n.kind() == ScoreNoteEvent.Kind.PITCHED
                && n.staffIndex() == 0 && n.staffCount() == 1
                && n.visualSize() == ScoreNoteEvent.VisualSize.FULL && n.beamCount() == 2
                && n.unbeamedDurationBeats() == 0 && n.augmentationDots() == 0
                && n.tupletDivisor() == 1 && !n.crossStaffBeam()
                && (n.stemDirection() == 0 || n.stemDirection() == -1)
                && (n.articulations() & NoteOrnament.GRACE) == 0;
    }

    private static boolean sameTouchPosition(float x, float y, float stopX, float stopY, float gap) {
        return Math.abs(x - stopX) <= gap * .46f
                && Math.abs(y - (stopY - gap * 1.5f)) <= gap * .36f;
    }

    private static boolean sameShaftTouch(byte[] raster, int width, int height, float x, float y, float gap) {
        var shafts = PrintedBeamShafts.at(raster, width, height, x, y, gap,
                new PrintedBeamShafts.Budget(40_000));
        if (shafts.size() != 1 || shafts.get(0).direction() != 1) return false;
        var shaft = shafts.get(0);
        for (int stem = shaft.left() - 2; stem <= shaft.right(); stem++)
            if (ArtificialHarmonics.shaftCrossedDiamond(
                    raster, width, height, x, y - gap * 1.5f, gap, stem, -1, true)) return true;
        return false;
    }

    private static boolean naturalPair(byte[] labels,byte[] gray,int width,int height,
            float rx,float first,float second,float gap,int staffTop) {
        int left=Math.max(0,Math.round(rx-gap*.75f)), right=Math.min(width-1,Math.round(rx+gap*.78f));
        int top=Math.max(0,Math.round(first-gap*2.30f)), bottom=Math.min(height-1,Math.round(second+gap*2.30f));
        int w=right-left+1,h=bottom-top+1;
        byte[] local=new byte[w*h]; Arrays.fill(local,(byte)255);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int at=(top+y)*width+left+x;
            if(labels[at]==OmrMeasurePostProcessor.CLEF_OR_KEY && (gray[at]&255)<155) local[y*w+x]=0;
        }
        return natural(local,w,h,first-top-gap*.25f,gap,staffTop-top)
                && natural(local,w,h,second-top+gap*.25f,gap,staffTop-top);
    }

    private static boolean natural(byte[] local,int w,int h,float center,float gap,int staffTop) {
        for(int l=0;l<w;l++)
            for(int r=l+Math.round(gap*.55f);r<Math.min(w,l+Math.round(gap*1.12f));r++)
                if(OmrScoreInterpreter.provesNaturalMeterInk(local,w,h,l,r,center,gap,staffTop))
                    return true;
        return false;
    }

    private static float x(MeasureRegion m,float position,int width) {
        return (m.left()+position*(m.right()-m.left()))*width;
    }
}
