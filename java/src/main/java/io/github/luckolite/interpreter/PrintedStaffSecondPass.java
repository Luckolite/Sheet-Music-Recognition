// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** An explicit repeat-pass instruction owns a printed reduced auxiliary staff. */
final class PrintedStaffSecondPass {
    static final String SOURCE = "printed-auxiliary-second-pass:v1";
    static final String OPENING = "printed-auxiliary-opening:v1";

    private PrintedStaffSecondPass() {}

    private record System(int first, int end, List<PlayingTechniqueDetector.Staff> staffs) {}

    static ScorePageInterpretation capture(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height,
            int page) {
        var systems = systems(score.measures(), staffs, height);
        if (systems.isEmpty()) return score;
        var events = new ArrayList<>(score.expressiveEvents());
        int opening = 0, openingCount = systems.get(0).staffs().size();
        for (var system : systems) {
            int count = system.staffs().size();
            boolean continueOpening =
                    openingCount >= 3 && system.first() == opening && count == openingCount;
            for (int staff = 0; staff < count - 1; staff++) {
                var owner = system.staffs().get(staff);
                for (var word : words) {
                    if (!instruction(word.text())
                            || word.bottom() * height > owner.top()
                            || owner.top() - word.bottom() * height > owner.gap() * 3) continue;
                    int first = entrance(score, system, owner, word, width);
                    if (first < 0) continue;
                    int end = first;
                    while (end < system.end()
                            && printedRails(gray, width, height, score.measures().get(end), owner))
                        end++;
                    if (end > first)
                        add(
                                events,
                                mark(
                                        SOURCE,
                                        page,
                                        staff,
                                        count,
                                        first,
                                        end,
                                        word.left(),
                                        word.text()));
                }
            }
            if (!continueOpening) continue;
            for (int m = system.first(); m < system.end(); m++) {
                boolean supported = true;
                for (int staff = 0; staff < count - 1; staff++)
                    if (!printedRails(
                            gray,
                            width,
                            height,
                            score.measures().get(m),
                            system.staffs().get(staff))) supported = false;
                if (!supported) break;
                opening++;
            }
        }
        if (opening > 0 && page > 0)
            for (int staff = 0; staff < openingCount - 1; staff++)
                add(events, mark(OPENING, page, staff, openingCount, 0, opening, 0, ""));
        return events.size() == score.expressiveEvents().size()
                ? score
                : score.withExpressiveEvents(events);
    }

    private static List<System> systems(
            List<MeasureRegion> measures, List<PlayingTechniqueDetector.Staff> staffs, int height) {
        var result = new ArrayList<System>();
        for (int position = 0; position < staffs.size(); ) {
            var first = staffs.get(position);
            int count = first.count();
            if (first.index() != 0 || count < 2 || position + count > staffs.size()) {
                position++;
                continue;
            }
            var group = staffs.subList(position, position + count);
            position += count;
            var principal = group.get(count - 1);
            if (principal.gap() < 10
                    || principal.count() != count
                    || principal.index() != count - 1) continue;
            boolean small = true;
            for (int staff = 0; staff < count - 1; staff++) {
                var lane = group.get(staff);
                if (lane.index() != staff
                        || lane.count() != count
                        || lane.gap() < 6
                        || lane.gap() < principal.gap() * .53f
                        || lane.gap() > principal.gap() * .81f) small = false;
            }
            if (!small) continue;
            int start = -1, end = -1;
            for (int m = 0; m < measures.size(); m++) {
                var bar = measures.get(m);
                if (bar.top() * height <= first.top()
                        && bar.bottom() * height >= principal.bottom()) {
                    if (start < 0) start = m;
                    end = m + 1;
                } else if (start >= 0) break;
            }
            if (start >= 0) result.add(new System(start, end, group));
        }
        return result;
    }

    private static int entrance(
            ScorePageInterpretation score,
            System system,
            PlayingTechniqueDetector.Staff owner,
            PlayingTechniqueDetector.Word word,
            int width) {
        for (int m = system.first(); m < system.end(); m++) {
            var bar = score.measures().get(m);
            if (Math.abs(bar.left() - word.left()) * width > owner.gap() * 3.5f
                    || word.right() >= bar.right()) continue;
            int measure = m;
            if (score.notes().stream()
                    .noneMatch(
                            n ->
                                    n.measureIndex() >= system.first()
                                            && n.measureIndex() < measure
                                            && n.staffIndex() == owner.index()
                                            && n.staffCount() == owner.count())) return m;
        }
        return -1;
    }

    private static boolean printedRails(
            byte[] gray,
            int width,
            int height,
            MeasureRegion bar,
            PlayingTechniqueDetector.Staff staff) {
        int votes = 0;
        int offset = Math.max(2, Math.round(staff.gap() * .34f));
        for (int station = 1; station < 10; station++) {
            int x = Math.round((bar.left() + (bar.right() - bar.left()) * station / 10f) * width);
            if (x < 0 || x >= width) continue;
            int lines = 0;
            for (int line = 0; line < 5; line++) {
                int nominal = Math.round(staff.top() + staff.gap() * line);
                for (int y = nominal - 2; y <= nominal + 2; y++) {
                    if (y < offset || y + offset >= height) continue;
                    int grayValue = gray[y * width + x] & 255;
                    if (grayValue <= 170
                            && (gray[(y - offset) * width + x] & 255) >= grayValue + 12
                            && (gray[(y + offset) * width + x] & 255) >= grayValue + 12) {
                        lines++;
                        break;
                    }
                }
            }
            if (lines >= 4) votes++;
        }
        return votes >= 5;
    }

    private static void add(List<ScoreExpressiveEvent> events, ScoreExpressiveEvent mark) {
        if (events.stream().noneMatch(e -> e.eventId().equals(mark.eventId()))) events.add(mark);
    }

    static boolean instruction(String text) {
        String phrase = text.toLowerCase(Locale.ROOT).strip().replaceAll("\s+", " ");
        return phrase.equals("2nd time only") || phrase.equals("second time only");
    }

    private static ScoreExpressiveEvent mark(
            String source,
            int page,
            int staff,
            int count,
            int first,
            int end,
            float x,
            String text) {
        String id = source + ":" + page + ":" + first + ":" + staff;
        return new ScoreExpressiveEvent(
                id,
                ScoreExpressiveEvent.Kind.UNRESOLVED_DIRECTION,
                Optional.of(new ScoreAnchor(first, 0)),
                Optional.of(new ScoreAnchor(end, 0)),
                ScoreExpressiveEvent.Scope.VOICE,
                staff,
                count,
                Optional.empty(),
                ScoreExpressiveEvent.Strength.UNSPECIFIED,
                "",
                List.of(new ScoreExpressiveEvent.Evidence(source, page, x, staff, count, text)));
    }
}
