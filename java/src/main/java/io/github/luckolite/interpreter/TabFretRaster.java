// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;

/** Fret-sized OCR crops, separated from string rules, barlines and staff labels. */
public final class TabFretRaster {
    private TabFretRaster() {}

    public record Crop(
            int left, int top, int right, int bottom, int string, float stringY, float gap) {}

    /** Tight OCR boxes can separate a fret from an attached slur or bend curve. */
    public static List<Crop> crops(
            byte[] gray,
            int w,
            int h,
            List<TablatureDecoder.Staff> tabs,
            List<TablatureDecoder.Word> words) {
        var result = new ArrayList<>(crops(gray, w, h, tabs));
        for (var t : tabs)
            for (var v : words) {
                if (!v.text().matches("[0-9]{1,2}")) continue;
                float x = (v.left() + v.right()) * .5f * w, y = (v.top() + v.bottom()) * .5f * h;
                int string = Math.round((y - t.top()) / t.gap());
                float cy = t.top() + string * t.gap();
                if (string < 0
                        || string >= t.stringCount()
                        || Math.abs(y - cy) > t.gap() * .24f
                        || (v.bottom() - v.top()) * h < t.gap() * .5f
                        || (v.bottom() - v.top()) * h > t.gap() * 1.1f
                        || (v.right() - v.left()) * w < t.gap() * .35f
                        || (v.right() - v.left()) * w > t.gap() * 1.4f) continue;
                float first =
                        result.stream()
                                .filter(
                                        c ->
                                                Math.abs(
                                                                c.stringY()
                                                                        - t.top()
                                                                        - c.string() * t.gap())
                                                        < t.gap() * .2f)
                                .map(c -> (float) c.left())
                                .min(Float::compare)
                                .orElse((float) w);
                if (x < first - t.gap() * .3f
                        || t.bars().stream().anyMatch(b -> Math.abs(b - x) < t.gap() * .3f))
                    continue;
                if (result.stream()
                        .anyMatch(
                                c ->
                                        Math.abs(c.stringY() - cy) < t.gap() * .3f
                                                && x > c.left() - t.gap() * .3f
                                                && x < c.right() + t.gap() * .3f)) continue;
                if (obscured(gray, w, h, x, cy, t.gap())) continue;
                result.add(
                        new Crop(
                                Math.max(0, Math.round(v.left() * w - t.gap() * .2f)),
                                Math.max(
                                        0,
                                        Math.min(
                                                Math.round(v.top() * h),
                                                Math.round(cy - t.gap() * .45f))),
                                Math.min(w, Math.round(v.right() * w + t.gap() * .1f)),
                                Math.min(
                                        h,
                                        Math.max(
                                                Math.round(v.bottom() * h),
                                                Math.round(cy + t.gap() * .45f))),
                                string,
                                cy,
                                t.gap()));
            }
        return List.copyOf(result);
    }

    public static List<TablatureDecoder.Word> reconcile(
            List<TablatureDecoder.Staff> tabs,
            List<Crop> crops,
            List<TablatureDecoder.Word> isolated,
            List<TablatureDecoder.Word> legacy,
            int w,
            int h) {
        var result = new ArrayList<TablatureDecoder.Word>();
        for (var c : crops) {
            float x = (c.left() + c.right()) * .5f;
            var votes = new LinkedHashMap<String, Integer>();
            for (var v : isolated)
                if (Math.abs((v.left() + v.right()) * .5f * w - x) < 1
                        && Math.abs((v.top() + v.bottom()) * .5f * h - c.stringY()) < 1)
                    votes.merge(v.text(), 2, Integer::sum);
            for (var v : legacy)
                if (v.text().matches("[0-9]{1,2}|[xX]")
                        && Math.abs((v.left() + v.right()) * .5f * w - x) < c.gap() * .3f
                        && Math.abs((v.top() + v.bottom()) * .5f * h - c.stringY()) < c.gap() * .24f
                        && (v.right() - v.left()) * w > (c.right() - c.left()) * .65f
                        && (v.right() - v.left()) * w < (c.right() - c.left()) * 1.4f)
                    votes.merge(v.text(), 1, Integer::sum);
            String value = null;
            int best = 0;
            for (var v : votes.entrySet())
                if (v.getValue() > best) {
                    value = v.getKey();
                    best = v.getValue();
                }
            if (value != null)
                result.add(
                        new TablatureDecoder.Word(
                                value,
                                c.left() / (float) w,
                                Math.max(0, (c.stringY() - c.gap() * .3f) / h),
                                c.right() / (float) w,
                                Math.min(1, (c.stringY() + c.gap() * .3f) / h)));
        }
        // Preserve literal annotations outside string lanes, including numeric bend amounts.
        // Typed consumers still require attached ink/ownership; these words are not fret votes.
        for (var v : legacy)
            if (tabs.stream()
                    .noneMatch(
                            t -> {
                                float y = (v.top() + v.bottom()) * .5f * h;
                                int string = Math.round((y - t.top()) / t.gap());
                                return string >= 0
                                        && string < t.stringCount()
                                        && Math.abs(y - t.top() - string * t.gap())
                                                <= t.gap() * .47f;
                            })) result.add(v);
        for (var v : legacy)
            if (v.text().matches(".*[hHpPbBrR/\\\\~<>].*"))
                for (var t : tabs) {
                    float y = (v.top() + v.bottom()) * .5f * h;
                    int string = Math.round((y - t.top()) / t.gap());
                    if (string < 0
                            || string >= t.stringCount()
                            || Math.abs(y - t.top() - string * t.gap()) > t.gap() * .35f) continue;
                    var parsed =
                            TabNotation.parse(
                                    v.text(),
                                    v.left() * w,
                                    v.right() * w,
                                    t.top() + string * t.gap(),
                                    string);
                    if (parsed.isEmpty() || parsed.stream().noneMatch(f -> f.marks() != 0))
                        continue;
                    var matched = new ArrayList<TablatureDecoder.Word>();
                    for (var f : parsed)
                        for (var note : result)
                            if (note.text().equals(Integer.toString(f.fret()))
                                    && Math.abs((note.top() + note.bottom()) * .5f * h - f.y())
                                            < t.gap() * .3f
                                    && Math.abs((note.left() + note.right()) * .5f * w - f.x())
                                            < t.gap() * .6f) {
                                matched.add(note);
                                break;
                            }
                    if (matched.size() == parsed.size()) {
                        result.removeAll(matched);
                        result.add(v);
                    }
                }
        return List.copyOf(result);
    }

    public static byte[] clean(byte[] gray, int w, int h, List<TablatureDecoder.Staff> tabs) {
        byte[] out = gray.clone();
        for (var t : tabs)
            for (int s = 0; s < t.stringCount(); s++) {
                int cy = Math.round(t.top() + s * t.gap()), a = h, b = -1;
                for (int y = Math.max(1, Math.round(cy - t.gap() * .18f));
                        y < Math.min(h - 1, Math.round(cy + t.gap() * .18f));
                        y++) {
                    int ink = 0;
                    for (int x = 0; x < w; x++) if ((gray[y * w + x] & 255) < 230) ink++;
                    if (ink > w * .4f) {
                        a = Math.min(a, y);
                        b = Math.max(b, y);
                    }
                }
                if (b >= a)
                    for (int x = 0; x < w; x++)
                        if ((gray[(a - 1) * w + x] & 255) >= 160
                                || (gray[(b + 1) * w + x] & 255) >= 160)
                            for (int y = a; y <= b; y++) out[y * w + x] = (byte) 255;
                for (float bar : t.bars())
                    for (int y = Math.max(0, Math.round(cy - t.gap() * .7f));
                            y < Math.min(h, Math.round(cy + t.gap() * .7f));
                            y++)
                        for (int x = Math.max(0, Math.round(bar - t.gap() * .13f));
                                x < Math.min(w, Math.round(bar + t.gap() * .13f));
                                x++) out[y * w + x] = (byte) 255;
            }
        return out;
    }

    /** A long, centered enclosed counter distinguishes an open fret from OCR's O/1. */
    public static boolean openFret(byte[] clean, int w, Crop c) {
        int cw = c.right() - c.left(), ch = c.bottom() - c.top();
        if (cw < ch * .35f || cw > ch * .95f) return false;
        boolean[] seen = new boolean[cw * ch];
        int[] queue = new int[seen.length];
        int holes = 0;
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++) {
                int id = y * cw + x;
                if (seen[id] || (clean[(c.top() + y) * w + c.left() + x] & 255) < 160) continue;
                int read = 0, end = 0, area = 0, minY = ch, maxY = 0;
                long sumY = 0;
                boolean edge = false;
                queue[end++] = id;
                seen[id] = true;
                while (read < end) {
                    int p = queue[read++], px = p % cw, py = p / cw;
                    area++;
                    sumY += py;
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                    edge |= px == 0 || py == 0 || px == cw - 1 || py == ch - 1;
                    int source = (c.top() + py) * w + c.left() + px;
                    if (px > 0) end = enqueueLight(clean, source - 1, p - 1, seen, queue, end);
                    if (px + 1 < cw) end = enqueueLight(clean, source + 1, p + 1, seen, queue, end);
                    if (py > 0) end = enqueueLight(clean, source - w, p - cw, seen, queue, end);
                    if (py + 1 < ch) end = enqueueLight(clean, source + w, p + cw, seen, queue, end);
                }
                if (!edge
                        && area > cw * ch * .15f
                        && maxY - minY > ch * .5f
                        && sumY / (float) area > ch * .38f
                        && sumY / (float) area < ch * .62f
                        && ++holes > 1) return false;
            }
        return holes == 1;
    }

    private static int enqueueLight(
            byte[] clean, int source, int next, boolean[] seen, int[] queue, int end) {
        if (!seen[next] && (clean[source] & 255) >= 160) {
            seen[next] = true;
            queue[end++] = next;
        }
        return end;
    }

    private static boolean obscured(byte[] gray, int w, int h, float x, float cy, float gap) {
        for (float dy : new float[] {-gap * .8f, 0, gap * .8f}) {
            int ink = 0, total = 0;
            for (int y = Math.max(0, Math.round(cy + dy - gap * .8f));
                    y < Math.min(h, Math.round(cy + dy + gap * .8f));
                    y++)
                for (int xx = Math.max(0, Math.round(x - gap * .8f));
                        xx < Math.min(w, Math.round(x + gap * .8f));
                        xx++) {
                    total++;
                    if ((gray[y * w + xx] & 255) < 180) ink++;
                }
            if (total > 0 && ink > total * .65f) return true;
        }
        return false;
    }

    /** Read uncovered fret-sized ink on standalone tab without disturbing owned readings. */
    static List<TablatureDecoder.Staff> supplement(
            byte[] gray, int width, int height, List<TablatureDecoder.Staff> tabs,
            List<TablatureDecoder.Word> words, PortableOcr.Inference inference) throws Exception {
        byte[] cleaned = null;
        var result = new ArrayList<>(tabs);
        for (int index = 0; index < tabs.size(); index++) {
            var staff = tabs.get(index);
            if (staff.standardTop() >= 0) continue;
            var crops = crops(gray, width, height, List.of(staff));
            var splits = splitFretWords(crops, words, width, height, staff.gap());
            var readings = new HashMap<Crop, String>();
            var frets = new ArrayList<>(staff.frets());
            for (var split : splits) {
                if (split.joined()) frets.removeIf(split::ownsJoinedFret);
                for (int i = 0; i < split.crops().size(); i++)
                    readings.merge(split.crops().get(i), split.digits().get(i),
                            (old, next) -> old.equals(next) ? old : "");
            }
            for (var crop : crops) {
                float margin = staff.gap() * .2f;
                if (frets.stream().anyMatch(fret -> fret.string() == crop.string()
                        && fret.x() >= crop.left() - margin && fret.x() <= crop.right() + margin))
                    continue;
                String value = readings.get(crop);
                if (value == null || value.isEmpty()) {
                    if (cleaned == null) cleaned = clean(gray, width, height, tabs);
                    value = openFret(cleaned, width, crop) ? "0"
                            : isolatedFret(cleaned, gray, width, height, crop, inference);
                }
                if (value.isEmpty()) continue;
                int fret = value.equalsIgnoreCase("x") ? -1 : Integer.parseInt(value);
                frets.add(new TablatureDecoder.Fret(
                        (crop.left() + crop.right()) * .5f, crop.stringY(), crop.string(),
                        fret, 0, 0, 0, 0));
            }
            if (!frets.equals(staff.frets())) {
                frets.sort(Comparator.comparingDouble(TablatureDecoder.Fret::x));
                result.set(index, staff.withFrets(frets));
            }
        }
        return List.copyOf(result);
    }

    private record SplitFretWord(List<Crop> crops, List<String> digits, boolean joined) {
        boolean ownsJoinedFret(TablatureDecoder.Fret fret) {
            return fret.fret() == Integer.parseInt(String.join("", digits))
                    && fret.string() == crops.get(0).string()
                    && fret.x() > crops.get(0).right() && fret.x() < crops.get(1).left();
        }
    }

    private static List<SplitFretWord> splitFretWords(
            List<Crop> crops, List<TablatureDecoder.Word> words,
            int width, int height, float gap) {
        var readings = new ArrayList<SplitFretWord>();
        for (var word : words) {
            boolean separated = word.text().matches("[0-9]{1,2}(?:\s+[0-9]{1,2})+");
            if (!separated && !word.text().matches("[0-9]{2}")) continue;
            var digits = List.of(separated ? word.text().split("\s+") : word.text().split(""));
            float left = word.left() * width, right = word.right() * width;
            float centerY = (word.top() + word.bottom()) * .5f * height;
            var ink = crops.stream().filter(crop -> Math.abs(crop.stringY() - centerY) < gap * .25f
                    && crop.left() >= left - gap * .2f && crop.right() <= right + gap * .2f)
                    .sorted(Comparator.comparingInt(Crop::left)).toList();
            if (ink.size() != digits.size()) continue;
            if (!separated && ink.get(1).left() - ink.get(0).right() < gap * .55f) continue;
            boolean valid = true;
            for (int i = 0; i < digits.size(); i++) {
                var crop = ink.get(i);
                String value = digits.get(i);
                if (Integer.parseInt(value) > 36
                        || value.length() == 2
                                && crop.right() - crop.left() < (crop.bottom() - crop.top()) * .65f)
                    valid = false;
            }
            if (valid) readings.add(new SplitFretWord(ink, digits, !separated));
        }
        return readings;
    }

    private static String isolatedFret(
            byte[] cleaned, byte[] gray, int width, int height, Crop crop,
            PortableOcr.Inference inference) throws Exception {
        if (inference == null) return "";
        int cropWidth = crop.right() - crop.left(), cropHeight = crop.bottom() - crop.top();
        int contentWidth = Math.max(1, Math.round(48f * cropWidth / cropHeight));
        int paddedWidth = Math.max(320, contentWidth);
        String value = "";
        for (byte[] raster : List.of(cleaned, gray)) {
            var input = PortableOcr.normalizeGray(raster, width, height,
                    crop.left(), crop.top(), crop.right(), crop.bottom(),
                    contentWidth, 48, paddedWidth, false);
            var reading = OcrCtcDecoder.decode(
                    inference.recognize(input, paddedWidth, 48), inference.dictionary(), 0);
            String text = reading.text();
            if (!(reading.confidence() >= (raster == cleaned ? .98f : .95f)) || !text.matches("[0-9]{1,2}|[xX]")
                    || text.length() == 2 && (cropWidth < cropHeight * .65f
                            || Integer.parseInt(text) > 36)
                    || !value.isEmpty() && !value.equalsIgnoreCase(text)) return "";
            value = text;
        }
        return value;
    }

    public static List<Crop> crops(byte[] gray, int w, int h, List<TablatureDecoder.Staff> tabs) {
        var result = new ArrayList<Crop>();
        for (var t : tabs) {
            var row = new ArrayList<Crop>();
            for (int s = 0; s < t.stringCount(); s++) {
                int cy = Math.round(t.top() + s * t.gap()),
                        lo = Math.max(0, Math.round(cy - t.gap() * .6f)),
                        hi = Math.min(h, Math.round(cy + t.gap() * .6f));
                boolean[] rule = new boolean[hi - lo], vertical = new boolean[w];
                for (int y = lo; y < hi; y++) {
                    int ink = 0, ruleInk = 0;
                    for (int x = 0; x < w; x++) {
                        int value = gray[y * w + x] & 255;
                        if (value < 180) ink++;
                        if (value < 235) ruleInk++;
                    }
                    // Antialiased string fringes can join separate frets into one
                    // overwide crop even when only the darker rule core is continuous.
                    rule[y - lo] =
                            ink > w * .4f
                                    || Math.abs(y - cy) <= t.gap() * .18f && ruleInk > w * .4f;
                }
                int va = Math.max(0, Math.round(cy - t.gap() * .8f)),
                        vb = Math.min(h, Math.round(cy + t.gap() * .8f));
                for (int x = 0; x < w; x++) {
                    int ink = 0;
                    for (int y = va; y < vb; y++) if ((gray[y * w + x] & 255) < 180) ink++;
                    vertical[x] = ink > (vb - va) * .9f;
                }
                for (float bar : t.bars())
                    for (int x = Math.max(0, Math.round(bar - t.gap() * .13f));
                            x < Math.min(w, Math.round(bar + t.gap() * .13f));
                            x++) vertical[x] = true;
                var runs = new ArrayList<int[]>();
                int start = -1;
                for (int x = 0; x <= w; x++) {
                    boolean ink = false;
                    if (x < w && !vertical[x])
                        for (int y = lo; y < hi; y++)
                            if (!rule[y - lo] && (gray[y * w + x] & 255) < 180) {
                                ink = true;
                                break;
                            }
                    if (ink && start < 0) start = x;
                    if (!ink && start >= 0) {
                        if (!runs.isEmpty()
                                && start - runs.get(runs.size() - 1)[1] < t.gap() * .50f)
                            runs.get(runs.size() - 1)[1] = x;
                        else runs.add(new int[] {start, x});
                        start = -1;
                    }
                }
                for (var run : runs) {
                    int a = run[0], b = run[1], yt = h, yb = -1;
                    if (b - a < t.gap() * .12f || b - a > t.gap() * 1.7f) continue;
                    for (int y = lo; y < hi; y++)
                        if (!rule[y - lo])
                            for (int x = a; x < b; x++)
                                if (!vertical[x] && (gray[y * w + x] & 255) < 180) {
                                    yt = Math.min(yt, y);
                                    yb = Math.max(yb, y + 1);
                                }
                    if (yb - yt < t.gap() * .35f || Math.abs((yt + yb) * .5f - cy) > t.gap() * .24f)
                        continue;
                    // A meter numeral extends far beyond a fret's string lane.
                    int outside = 0;
                    for (int x = a; x < b; x++)
                        for (int y :
                                new int[] {
                                    Math.max(0, Math.round(cy - t.gap() * .72f)),
                                    Math.min(h - 1, Math.round(cy + t.gap() * .72f))
                                }) if ((gray[y * w + x] & 255) < 180) outside++;
                    if (!t.bars().isEmpty()
                            && a < t.bars().get(0) + t.gap() * 5
                            && yb - yt > t.gap() * 1.1f
                            && outside > (b - a) * .45f) continue;
                    if (b - a < t.gap() * .5f
                            && yb - yt > t.gap() * .95f
                            && outside > (b - a) * .22f) continue;
                    if (obscured(gray, w, h, (a + b) * .5f, cy, t.gap())) continue;
                    boolean longStem = false;
                    if (b - a < t.gap() * .2f || b - a < t.gap() * .8f && yb - yt > t.gap() * .95f)
                        for (int x = a; x < b; x++)
                            for (int direction : new int[] {-1, 1}) {
                                int count = 0, total = 0;
                                for (int j =
                                                Math.round(
                                                        -t.gap()
                                                                * (b - a < t.gap() * .2f
                                                                        ? .1f
                                                                        : .65f));
                                        j <= Math.round(t.gap() * 1.5f);
                                        j++) {
                                    int y = cy + direction * j;
                                    if (y < 0 || y >= h) continue;
                                    total++;
                                    if ((gray[y * w + x] & 255) < 180) count++;
                                }
                                if (count > total * .8f) longStem = true;
                            }
                    if (longStem) continue;
                    row.add(new Crop(a, yt, b, yb, s, t.top() + s * t.gap(), t.gap()));
                }
            }
            float header = 0;
            if (!t.bars().isEmpty() && t.bars().get(0) < w * .2f)
                header = t.bars().get(0) + t.gap() * 1.8f;
            // Unbarred ASCII tabs still have a vertical column of string names.
            for (var c : row)
                if (c.left() < w * .15f
                        && (t.bars().isEmpty()
                                || t.bars().get(0) >= w * .2f
                                || c.left() < t.bars().get(0))) {
                    long aligned =
                            row.stream()
                                    .filter(v -> Math.abs(v.left() - c.left()) < t.gap() * .2f)
                                    .map(Crop::string)
                                    .distinct()
                                    .count();
                    if (aligned >= 4) header = Math.max(header, c.right() + t.gap() * .15f);
                }
            for (var c : row) if (c.left() > header) result.add(c);
        }
        return List.copyOf(result);
    }
}
