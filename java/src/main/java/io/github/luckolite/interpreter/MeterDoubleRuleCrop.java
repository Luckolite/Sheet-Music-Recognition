// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;

/** Detaches verified boundary ink from an otherwise unread meter crop. */
final class MeterDoubleRuleCrop {
    private MeterDoubleRuleCrop() {}

    private record Stroke(int left,int right) {}

    /** Recover a numeral slot from the local rails when skew hid its original crop. */
    static List<MeterChangeDetector.Crop> supplement(
            List<MeterChangeDetector.Crop> existing, byte[] labels, byte[] gray, int width, int height,
            List<MeasureRegion> measures, List<ScoreNoteEvent> notes) {
        if (gray == null || labels == null || gray.length != (long) width * height
                || labels.length != gray.length || measures.size() < 2 || notes.isEmpty())
            return existing;
        var first = new ScoreNoteEvent[measures.size()];
        for (var note : notes) {
            int bar = note.measureIndex();
            if (bar <= 0 || bar >= first.length || note.kind() != ScoreNoteEvent.Kind.PITCHED) continue;
            if (first[bar] == null || note.positionInMeasure() < first[bar].positionInMeasure())
                first[bar] = note;
        }
        var result = new ArrayList<>(existing);
        List<RawStaffLineDetector.StaffLines> staffs = null;
        for (int i = 1; i < measures.size() && result.size() < 48; i++) {
            var note = first[i];
            if (note == null || note.staffStep() < -2 || note.staffStep() > 10) continue;
            var prior = measures.get(i - 1);
            var bar = measures.get(i);
            float left = bar.left() * width, right = prior.right() * width;
            float barHeight = (bar.bottom() - bar.top()) * height;
            if (left < right || left - right > barHeight * .24f
                    || Math.abs(bar.top() - prior.top()) * height > barHeight * .12f) continue;
            float attack = left + note.positionInMeasure() * (bar.right() * width - left);
            if (attack - left < barHeight * .30f || attack - left > barHeight * .80f) continue;
            if (staffs == null) staffs = RawStaffLineDetector.detect(gray, width, height);
            float boundary = (left + right) * .5f;
            for (var track : staffs) {
                float gap = track.gap();
                float bottomHint = note.pageY() * height + note.staffStep() * gap * .5f;
                if (Math.abs(bottomHint - track.bottom()) > gap * 1.7f
                        || attack - left < gap * 3.1f) continue;
                var rails = DottedDyadMeasureSeparator.localRails(gray, width, height,
                        Math.round(boundary - gap * .85f), bottomHint, gap);
                if (rails == null) continue;
                gap = rails[1];
                int top = Math.round(rails[0] - gap * 4);
                int cropRight = Math.round(Math.min(left + gap * 2.45f, attack - gap));
                var seed = new MeterChangeDetector.Crop(
                        Math.max(0, Math.round(boundary - gap * .44f)),
                        Math.max(0, Math.round(top - gap * .4f)), cropRight,
                        Math.min(height, Math.round(rails[0] + gap * .4f)), top, gap);
                var crop = trimPair(seed, gray, width, height, left);
                if (crop == seed || !hasStackedSymbols(crop, labels, width)
                        || MeterChangeDetector.followingMeasure(crop, width, height, measures) != i
                        || !MeterChangeDetector.precedesNotes(
                                crop, labels, gray, width, height, i, measures, notes)
                        || result.stream().anyMatch(
                        old -> Math.abs(old.firstLine() - crop.firstLine()) < crop.gap()
                                && old.left() < crop.right() && old.right() > crop.left()))
                    continue;
                result.add(crop);
                break;
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasStackedSymbols(MeterChangeDetector.Crop crop, byte[] labels, int width) {
        int min = Math.max(5, Math.round(crop.gap() * crop.gap() * .24f));
        int middle = Math.round(crop.firstLine() + crop.gap() * 2);
        int upper = 0, lower = 0;
        for (int y = crop.top(); y < crop.bottom(); y++)
            for (int x = crop.left(); x < crop.right(); x++)
                if (labels[y * width + x] == OmrMeasurePostProcessor.SYMBOL) {
                    if (y < middle) upper++;
                    else lower++;
                }
        return upper >= min && lower >= min;
    }

    static MeterChangeDetector.Crop trim(MeterChangeDetector.Crop crop,byte[] gray,
            int width,int height,float ownerLeft) {
        return trim(crop, gray, width, height, ownerLeft, false);
    }

    static MeterChangeDetector.Crop trimPair(
            MeterChangeDetector.Crop crop, byte[] gray, int width, int height, float ownerLeft) {
        return trim(crop, gray, width, height, ownerLeft, true);
    }

    private static MeterChangeDetector.Crop trim(MeterChangeDetector.Crop crop, byte[] gray,
            int width, int height, float ownerLeft, boolean requiresPair) {
        if (gray==null || gray.length!=(long)width*height || !Float.isFinite(ownerLeft)
                || crop==null || crop.gap()<8 || crop.gap()>32
                || crop.left()<0 || crop.right()>width || crop.top()<0 || crop.bottom()>height)
            return crop;
        if (ownerLeft<crop.left() || ownerLeft>crop.right()) return crop;
        var trimmed = trimRules(crop, gray, width, height, ownerLeft, requiresPair);
        if (trimmed != crop) return trimmed;

        // A wide crop can begin in verified paper before both bars. Discard only
        // that paper, and require the same complete pair past the shorter prefix.
        int left = crop.left() + Math.round(crop.gap() * .42f);
        if (left >= ownerLeft || left >= crop.right()) return crop;
        for (int x = crop.left(); x < left; x++)
            if (!paperBetweenRules(gray, width, height, x, crop.firstLine(), crop.gap())) return crop;
        var narrowed = new MeterChangeDetector.Crop(
                left, crop.top(), crop.right(), crop.bottom(), crop.firstLine(), crop.gap());
        var recovered = trimRules(narrowed, gray, width, height, ownerLeft, true);
        return recovered == narrowed ? crop : recovered;
    }

    private static MeterChangeDetector.Crop trimRules(
            MeterChangeDetector.Crop crop, byte[] gray, int width, int height,
            float ownerLeft, boolean requiresPair) {
        float gap=crop.gap();
        int end=Math.min(crop.right()-Math.round(1.3f*gap),
                Math.min(crop.left()+Math.round(gap*1.28f),Math.round(ownerLeft+gap*.3f)));
        if (end<=crop.left()) return crop;
        int maxSingleWidth = Math.max(2, Math.round(gap * .34f));
        int maxPairedWidth = Math.max(2, Math.round(gap * .43f));
        Stroke first=null,second=null;
        for (int x=crop.left();x<end;) {
            if (!spansStaff(gray,width,height,x,crop.firstLine(),gap)) {
                x++;
                continue;
            }
            int left=x;
            do { x++; }
            while (x<end && spansStaff(gray,width,height,x,crop.firstLine(),gap));
            if (x>=end || x-left<2 || x-left>maxPairedWidth) return crop;
            var stroke=new Stroke(left,x);
            if(first==null) first=stroke;
            else if(second==null) second=stroke;
            else return crop;
        }
        if (first == null || first.left - crop.left() > gap * .42f) return crop;
        int boundary;
        if (second == null) {
            if (requiresPair) return crop;
            float distance = ownerLeft - first.right;
            if (first.right - first.left > maxSingleWidth
                    || distance < gap * .24f || distance > gap * .76f) return crop;
            boundary = first.right;
        } else {
            if (second.left - first.right < 2
                    || second.left - first.right > gap * .43f
                    || second.right - first.left > gap * .93f) return crop;
            if ((first.right - first.left > maxSingleWidth
                            || second.right - second.left > maxSingleWidth)
                    && (extendsBeyondStaff(gray, width, height, crop, first)
                            || extendsBeyondStaff(gray, width, height, crop, second))) return crop;
            boundary = second.right;
        }
        if (boundary + 2 >= crop.right()) return crop;
        // Prefer the paper immediately beside the established rule. A displaced edge
        // of a verified pair can taper for a few more connected columns.
        if (!paperBetweenRules(gray, width, height, boundary, crop.firstLine(), gap)
                || !paperBetweenRules(gray, width, height, boundary + 1, crop.firstLine(), gap)) {
            int next = boundary + 1;
            if (paperBetweenRules(gray, width, height, next, crop.firstLine(), gap)
                    && paperBetweenRules(gray, width, height, next + 1, crop.firstLine(), gap))
                boundary = next;
            else if (second != null)
                boundary = pairedRuleFringe(gray, width, height, crop, boundary);
            else return crop;
            if (boundary < 0) return crop;
        }
        return new MeterChangeDetector.Crop(
                boundary, crop.top(), crop.right(), crop.bottom(), crop.firstLine(), gap);
    }

    private static int pairedRuleFringe(
            byte[] gray, int width, int height, MeterChangeDetector.Crop crop, int start) {
        float gap = crop.gap();
        int top = crop.firstLine();
        if (DottedDyadMeasureSeparator.continuesBeyondStaff(
                gray, width, height, start - 1, top, Math.round(top + 4 * gap), gap)) return -1;
        int last = Math.min(crop.right() - 3, start + Math.round(gap * .24f));
        int previous = Integer.MAX_VALUE;
        for (int x = start; x <= last; x++) {
            if (paperBetweenRules(gray, width, height, x, top, gap)
                    && paperBetweenRules(gray, width, height, x + 1, top, gap)) return x;
            int samples = 0;
            for (int space = 0; space < 4; space++) {
                int first = Math.round(top + (space + .22f) * gap);
                int end = Math.round(top + (space + .80f) * gap);
                if (first < 1 || end >= height - 1) return -1;
                for (int y = first; y <= end; y++) {
                    if ((gray[y * width + x] & 255) >= 155) continue;
                    if ((gray[(y - 1) * width + x - 1] & 255) >= 155
                            && (gray[y * width + x - 1] & 255) >= 155
                            && (gray[(y + 1) * width + x - 1] & 255) >= 155) return -1;
                    samples++;
                }
            }
            if (samples == 0 || samples >= previous) return -1;
            previous = samples;
        }
        return -1;
    }

    private static boolean extendsBeyondStaff(
            byte[] gray, int width, int height, MeterChangeDetector.Crop crop, Stroke stroke) {
        int middle = (stroke.left + stroke.right) / 2;
        return DottedDyadMeasureSeparator.continuesBeyondStaff(
                gray, width, height, middle, crop.firstLine(),
                Math.round(crop.firstLine() + 4 * crop.gap()), crop.gap());
    }

    private static boolean spansStaff(byte[] gray,int width,int height,int x,int top,float gap) {
        for (int space=0;space<4;space++) {
            int first=Math.round(top+(space+.22f)*gap);
            int last=Math.round(top+(space+.80f)*gap);
            if (first<0 || last>=height) return false;
            int hit=0;
            for (int y=first;y<=last;y++) if ((gray[y*width+x]&255)<155) hit++;
            if (hit<(last-first+1)*.86f) return false;
        }
        return true;
    }

    private static boolean paperBetweenRules(byte[] gray,int width,int height,int x,int top,float gap) {
        if (x<0 || x>=width) return false;
        int ink=0,checked=0;
        for(int space=0;space<4;space++) {
            int first=Math.round(top+(space+.22f)*gap);
            int last=Math.round(top+(space+.80f)*gap);
            if (first<0 || last>=height) return false;
            for (int y=first;y<=last;y++) {
                checked++;
                if ((gray[y*width+x]&255)<155) ink++;
            }
        }
        return ink<=checked*.10f;
    }
}
