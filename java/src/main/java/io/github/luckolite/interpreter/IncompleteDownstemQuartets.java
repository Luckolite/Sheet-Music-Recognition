// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Completes the missing terminal owner of an otherwise retained two-rail group. */
final class IncompleteDownstemQuartets {
    private IncompleteDownstemQuartets() {}

    static ScorePageInterpretation complete(ScorePageInterpretation score, byte[] gray, byte[] raw,
            int width, int height) {
        if (gray == null || raw == null || gray.length != (long) width * height
                || raw.length != gray.length) return score;
        var source = score.beamSource();
        var notes = score.notes();
        var byMeasure = new HashMap<Integer, List<Integer>>();
        for (int i = 0; i < notes.size(); i++)
            byMeasure.computeIfAbsent(notes.get(i).measureIndex(), unused -> new ArrayList<>()).add(i);
        var grouped = new HashMap<Integer, List<Integer>>();
        for (var group : source.resolve().groups()) {
            if (group.columns().stream().anyMatch(c -> !c.usable() || c.owners().size() != 1))
                continue;
            var members = group.columns().stream().map(c -> c.owners().get(0)).toList();
            boolean regular = group.source().tupletMark() == null && group.source().hooks().isEmpty()
                    && group.source().bridges().stream().allMatch(mask -> mask == 3);
            for (int member : members)
                grouped.merge(member, regular ? members : List.of(), (a,b) -> List.of());
        }
        var beams = source;
        for (var entry : byMeasure.entrySet()) {
            var owners = entry.getValue();
            int measure = entry.getKey();
            if (measure < 0 || measure >= score.measures().size() || owners.size() != 12
                    || score.rests().stream().anyMatch(r -> r.measureIndex() == measure)) continue;
            owners.sort(Comparator.comparingDouble(i -> notes.get(i).positionInMeasure()));
            if (!owners.subList(0,3).equals(grouped.get(owners.get(0)))
                    || grouped.containsKey(owners.get(3))
                    || !owners.subList(4,8).equals(grouped.get(owners.get(4)))
                    || !owners.subList(8,12).equals(grouped.get(owners.get(8)))) continue;
            if (owners.stream().map(notes::get).anyMatch(n ->
                    !ClosedMonophonicSixteenths.regularQuartetHead(n) || n.stemDirection() != -1
                            || n.tupletDivisor() != 1 || n.augmentationDots() != 0 || n.tiedFromPrevious()))
                continue;
            var bar = score.measures().get(measure);
            if (bar.cadenceQuarterBeats() != 0) continue;
            var boundary = owners.subList(2,5).stream().map(notes::get).toList();
            if (!terminalEdge(gray, width, height, bar, boundary)
                    || !terminalEdge(raw, width, height, bar, boundary)) continue;
            beams = ClosedArpeggioTriplets.replaceGroups(beams, owners.subList(0,4),
                    List.of(List.of(3,3,3)), 0);
        }
        return beams == source ? score : score.withBeamSource(beams);
    }

    private static boolean terminalEdge(byte[] ink, int width, int height,
            MeasureRegion bar, List<ScoreNoteEvent> boundary) {
        float gap = (bar.bottom() - bar.top()) * height / 8.2f;
        if (gap < 10 || gap > 25) return false;
        var budget = new PrintedBeamShafts.Budget(90_000);
        var axes = new ArrayList<PrintedBeamShafts.Shaft>(3);
        for (var note : boundary) {
            float x = (bar.left() + (bar.right() - bar.left()) * note.positionInMeasure()) * width;
            var found = PrintedBeamShafts.at(ink, width, height, x, note.pageY() * height, gap, budget);
            if (budget.refused() || found.size() != 1 || found.get(0).direction() != 1) return false;
            axes.add(found.get(0));
        }
        return rails(ink, width, height, axes.get(0), axes.get(1), gap, true)
                && rails(ink, width, height, axes.get(1), axes.get(2), gap, false);
    }

    private static boolean rails(byte[] ink, int width, int height, PrintedBeamShafts.Shaft a,
            PrintedBeamShafts.Shaft b, float gap, boolean joined) {
        int x0 = (a.left() + a.right()) / 2, x1 = (b.left() + b.right()) / 2;
        int y0 = a.endY(), y1 = b.endY();
        if (x1 - x0 < gap * 1.55f || x1 - x0 > gap * 3.65f
                || x0 < 0 || x1 >= width || Math.min(y0,y1) < gap * 1.2f
                || Math.max(y0,y1) >= height) return false;
        int inset = Math.round(gap * .25f);
        int outer = 0, inner = 0, split = 0, total = x1 - x0 - 2 * inset + 1;
        for (int x = x0 + inset; x <= x1 - inset; x++) {
            int y = Math.round(y0 + (y1 - y0) * (x - x0) / (float) (x1 - x0));
            if (darkThree(ink, width, x, y - Math.round(.27f * gap))) outer++;
            if (darkThree(ink, width, x, y - Math.round(1.01f * gap))) inner++;
            for (int at = y - Math.round(.72f * gap); at <= y - Math.round(.58f * gap); at++) {
                if ((ink[at * width + x] & 255) > 190 && (ink[(at-1) * width + x] & 255) > 190) {
                    split++;
                    break;
                }
            }
        }
        return joined ? outer >= total * .90f && inner >= total * .90f && split >= total * .76f
                : Math.max(outer,inner) <= total * .20f;
    }

    private static boolean darkThree(byte[] ink, int width, int x, int y) {
        return (ink[(y-1) * width + x] & 255) < 160
                && (ink[y * width + x] & 255) < 160 && (ink[(y+1) * width + x] & 255) < 160;
    }
}