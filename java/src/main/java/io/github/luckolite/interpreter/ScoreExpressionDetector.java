// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.*;
import static io.github.luckolite.interpreter.ScoreExpressiveEvent.*;

/** OCR directions attached to proved written columns; optical fractions never become beats. */
public final class ScoreExpressionDetector {

    private static boolean coveredPedalWord(
            PlayingTechniqueDetector.Word word, List<PlayingTechniqueDetector.Word> words) {
        String token = word.text().trim().toLowerCase(Locale.ROOT);
        if (!token.matches("ped(?:ale|\\.?)")) return false;
        float height = word.bottom() - word.top();
        if (!(height > 0) || !(word.right() > word.left())) return false;
        for (var other : words) {
            String phrase = other.text().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!phrase.matches("(?:con|senza) ped(?:ale|\\.?)")) continue;
            if (!phrase.endsWith(" " + token)) continue;
            float otherHeight = other.bottom() - other.top();
            float overlap =
                    Math.min(word.bottom(), other.bottom()) - Math.max(word.top(), other.top());
            if (otherHeight > 0
                    && overlap >= .8f * Math.min(height, otherHeight)
                    && word.left() >= other.left()
                    && word.right() <= other.right()
                    && other.right() - other.left() > word.right() - word.left()) return true;
        }
        return false;
    }

    private static final String SOURCE = "printed-expression-word";
    private static final String LEDGER_CAPTION = "printed-expression-ledger-caption";
    private static final String SYMBOL = "printed-expression-symbol";
    private static final String TARGET = "expression-column:";
    private static final String REST_TARGET = "expression-rest-column:";
    private static final Set<Kind> KINDS =
            EnumSet.of(
                    Kind.RITARDANDO,
                    Kind.RALLENTANDO,
                    Kind.RITENUTO,
                    Kind.METRIC_MODULATION,
                    Kind.ACCELERANDO,
                    Kind.A_TEMPO,
                    Kind.TEMPO_PRIMO,
                    Kind.TEMPO_MARKING,
                    Kind.SAME_TEMPO,
                    Kind.SFORZANDO,
                    Kind.SFORZATO,
                    Kind.SFORZANDO_PIANO,
                    Kind.BREATH,
                    Kind.CAESURA,
                    Kind.UNRESOLVED_DIRECTION,
                    Kind.PEDAL_DOWN,
                    Kind.PEDAL_UP);

    private ScoreExpressionDetector() {}

    private record TempoBoundary(int measure, PlayingTechniqueDetector.Staff staff, float rank) {}

    private static TempoBoundary tempoBarline(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Staff> staffs,
            PlayingTechniqueDetector.Word word, byte[] gray, int width, int height, boolean sectional) {
        if (gray == null || width <= 0 || height <= 0 || gray.length != (long) width * height
                || !(word.left() >= 0 && word.right() <= 1 && word.right() > word.left()
                        && word.top() >= 0 && word.bottom() <= 1 && word.bottom() > word.top()))
            return null;
        float x = word.left() * width, top = word.top() * height, bottom = word.bottom() * height;
        float headerAllowance = sectional && ExpressiveDirectionText.tempoPartCaption(word.text()) ? 3.15f : 3;
        float middle = (top + bottom) * .5f;
        TempoBoundary best = null;
        boolean tied = false;
        var measures = score.measures();
        List<RawStaffLineDetector.StaffLines> rawRows = null;
        for (var staff : staffs) {
            float gap = staff.gap();
            if (!(gap >= 6) || staff.count() <= staff.index()) continue;
            float distance;
            if (middle < staff.top() && bottom <= staff.top() + gap * .3f)
                distance = Math.max(0, staff.top() - bottom);
            else if (middle > staff.bottom() && top >= staff.bottom() - gap * .3f)
                distance = Math.max(0, top - staff.bottom());
            else continue;
            if (distance > gap * 5.6f) continue;
            float center = (staff.top() + staff.bottom()) * .5f / height;
            for (int i = 0; i < measures.size(); i++) {
                var bar = measures.get(i);
                if (i > 0 && bar.equals(measures.get(i - 1))) continue;
                if (center < bar.top() || center > bar.bottom()) continue;
                // A below-staff word without a written onset must use the printed-rest proof.
                if (middle > staff.bottom() && !sectional
                        && !hasWrittenEntry(score, i)) continue;
                float left = bar.left() * width, right = bar.right() * width;
                float offset = x - left;
                if (word.right() * width <= left || offset < -gap * 1.6f
                        || offset > (right - left) * .24f) continue;
                if (offset > gap * headerAllowance
                        && (!sectional || offset > gap * 6 || !nearFirstWrittenColumn(score, i, x, width, gap)))
                    continue;
                float probe = Math.max(left + gap, Math.min(x, right - gap));
                var rails = PrintedDirectionStaff.printedRules(
                        gray, width, height, probe, staff.bottom(), gap);
                if (rails == null || Math.abs(rails[0] - staff.bottom()) > gap * .6f
                        || Math.abs(rails[1] - gap) > gap * .15f) {
                    if (rawRows == null) rawRows = RawStaffLineDetector.detect(gray, width, height);
                    if (!restorationRails(rawRows, staff, gray, width, height, probe)) continue;
                }
                float rank = (distance + Math.abs(offset) * .6f) / gap;
                if (best == null || rank < best.rank() - .15f) {
                    best = new TempoBoundary(i, staff, rank);
                    tied = false;
                } else if (Math.abs(rank - best.rank()) <= .15f && i != best.measure()) {
                    tied = true;
                }
            }
        }
        return tied ? null : best;
    }

    private static boolean hasWrittenEntry(ScorePageInterpretation score, int measure) {
        for (var note : score.notes()) if (note.measureIndex() == measure) return true;
        for (var rest : score.rests()) if (rest.measureIndex() == measure) return true;
        return false;
    }

    private static PlayingTechniqueDetector.Word localizedRehearsalTempo(
            PlayingTechniqueDetector.Word line, byte[] gray, int width, int height) {
        if (gray == null || gray.length != (long) width * height) return null;
        int left = Math.round(line.left() * width), right = Math.round(line.right() * width);
        int top = Math.round(line.top() * height), bottom = Math.round(line.bottom() * height);
        if (left < 0 || right > width || top < 0 || bottom > height || right <= left || bottom <= top)
            return null;
        int glyphHeight = bottom - top;
        int requiredGap = Math.max(5, (int) Math.ceil(glyphHeight * .08));
        int rightOfInitial = -1, emptyFrom = -1;
        boolean hasInk = false;
        for (int x = left; x < right && x - left <= glyphHeight * 4; x++) {
            boolean ink = false;
            for (int y = top; y < bottom && !ink; y++) ink = (gray[y * width + x] & 255) < 160;
            if (!ink) {
                if (hasInk && emptyFrom < 0) emptyFrom = x;
                continue;
            }
            if (emptyFrom >= 0 && x - emptyFrom >= requiredGap) {
                if (rightOfInitial < 0) {
                    if (emptyFrom - left > glyphHeight * 1.1f || x - emptyFrom < requiredGap * 2)
                        return null;
                    rightOfInitial = x;
                } else {
                    if (emptyFrom - rightOfInitial < glyphHeight * .65f) return null;
                    return new PlayingTechniqueDetector.Word(line.text(), rightOfInitial / (float) width,
                            line.top(), emptyFrom / (float) width, line.bottom(), line.admission());
                }
            }
            hasInk = true;
            emptyFrom = -1;
        }
        return null;
    }

    private static boolean clippedLentoInVerse(
            PlayingTechniqueDetector.Word word, List<PlayingTechniqueDetector.Word> words) {
        if (!word.text().strip().equalsIgnoreCase("lento")) return false;
        for (var line : words) {
            if (line.left() + .006f >= word.left() || line.right() <= word.right()
                    || ExpressiveDirectionText.sectionalTempoCaption(line.text()) != null) continue;
            float overlap = Math.min(line.bottom(), word.bottom()) - Math.max(line.top(), word.top());
            if (overlap < (word.bottom() - word.top()) * .6f) continue;
            // A second crop can read the leading V in a verse's "vento" as an L or I.
            if (line.text().strip().toLowerCase(Locale.ROOT).matches(
                    ".*[ ](?:lento|iento|vento)[ ].*")) return true;
        }
        return false;
    }

    private static boolean startsSystem(ScorePageInterpretation score, int index) {
        if (index == 0) return true;
        var bar = score.measures().get(index);
        var previous = score.measures().get(index - 1);
        return bar.top() > (previous.top() + previous.bottom()) * .5f;
    }

    private static boolean nearFirstWrittenColumn(
            ScorePageInterpretation score, int measure, float x, int width, float gap) {
        var bar = score.measures().get(measure);
        float position = Float.POSITIVE_INFINITY;
        for (var note : score.notes())
            if (note.measureIndex() == measure) position = Math.min(position, note.positionInMeasure());
        for (var rest : score.rests())
            if (rest.measureIndex() == measure) position = Math.min(position, rest.positionInMeasure());
        float first = (bar.left() + position * (bar.right() - bar.left())) * width;
        return Math.abs(x - first) <= gap * 1.25f;
    }

    private static boolean coveredTempoWord(
            PlayingTechniqueDetector.Word word, List<PlayingTechniqueDetector.Word> words) {
        String qualified = switch (word.text().strip().toLowerCase(Locale.ROOT)) {
            case "slower" -> "slightly slower";
            case "adagio" -> "molto adagio";
            default -> "";
        };
        if (qualified.isEmpty()) return false;
        for (var other : words) {
            if (!other.text().strip().replaceFirst("[.!]$", "").equalsIgnoreCase(qualified)
                    || other.left() >= word.left() || other.right() + .002f < word.right()) continue;
            float overlap = Math.min(word.bottom(), other.bottom()) - Math.max(word.top(), other.top());
            if (overlap >= (word.bottom() - word.top()) * .6f) return true;
        }
        return false;
    }

    /** A uniquely aligned sounding column can realize the tempo shared with a silent staff. */
    private static ScoreNoteEvent alignedTempoAttack(ScorePageInterpretation score, ScoreRestEvent rest) {
        if (rest.staffCount() < 2 || rest.isFullMeasure()) return null;
        ScoreNoteEvent selected = null;
        for (var note : score.notes()) {
            if (note.measureIndex() != rest.measureIndex() || note.staffCount() != rest.staffCount()
                    || note.staffIndex() == rest.staffIndex() || note.crossStaffBeam()
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || Math.abs(note.positionInMeasure() - rest.positionInMeasure()) > .008f) continue;
            if (selected != null && (selected.staffIndex() != note.staffIndex()
                    || Math.abs(selected.positionInMeasure() - note.positionInMeasure()) > .003f)) return null;
            selected = note;
        }
        return selected;
    }

    private static boolean localTempoNote(
            ScorePageInterpretation score, ScoreNoteEvent note, PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word word, int width, int height) {
        if (note == null || staff == null) return false;
        var measure = score.measures().get(note.measureIndex());
        float column = measure.left() + note.positionInMeasure() * (measure.right() - measure.left());
        float vertical = staff.top() - word.bottom() * height;
        return vertical >= -staff.gap() * .3f && vertical <= staff.gap() * 2.5f
                && Math.abs(column - word.left()) * width <= staff.gap() * .85f;
    }

    private static boolean restorationRails(
            List<RawStaffLineDetector.StaffLines> rows, PlayingTechniqueDetector.Staff staff,
            byte[] gray, int width, int height, float x) {
        float gap = staff.gap();
        int left = Math.max(0, Math.round(x - gap)), right = Math.min(width - 1, Math.round(x + gap));
        if (right - left < 4) return false;
        for (var row : rows) {
            if (Math.abs(row.bottom() - staff.bottom()) > gap * .6f
                    || Math.abs(row.gap() - gap) > gap * .15f) continue;
            int supported = 0;
            for (int y : row.rows()) {
                if (y <= 0 || y + 1 >= height) continue;
                int ink = 0;
                for (int col = left; col <= right; col++)
                    if ((gray[(y - 1) * width + col] & 255) <= 175
                            || (gray[y * width + col] & 255) <= 175
                            || (gray[(y + 1) * width + col] & 255) <= 175) ink++;
                if (ink * 2 >= right - left + 1) supported++;
            }
            if (supported >= 4) return true;
        }
        return false;
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height,
            PrintedExpressionGlyphs glyphs) {
        var combined = new ArrayList<>(words);
        var symbols =
                glyphs.detect(score, staffs, gray, width, height).stream()
                        .map(m -> m.word(width, height))
                        .toList();
        combined.addAll(symbols);
        var releaseScore = removeCaesuraRestAliases(score, symbols);
        return apply(releaseScore, combined, staffs, gray, width, height, Set.copyOf(symbols));
    }

    /** Semantic rest detection may claim the first slash of a proved caesura. */
    private static ScorePageInterpretation removeCaesuraRestAliases(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Word> symbols) {
        List<ScoreRestEvent> cleaned = null;
        for (var symbol : symbols) {
            if (!symbol.text().equals("caesura")) continue;
            ScoreRestEvent owned = null;
            for (var rest : score.rests()) {
                if (rest.kind() != ScoreRestEvent.Kind.LITERAL || rest.durationBeats() != .5
                        || rest.measureIndex() < 0 || rest.measureIndex() >= score.measures().size()
                        || rest.pageHeight() <= 0) continue;
                var measure = score.measures().get(rest.measureIndex());
                float x = measure.left() + rest.positionInMeasure() * (measure.right() - measure.left());
                if (!(x > symbol.left() && x < symbol.right()
                        && rest.pageY() > symbol.top() && rest.pageY() < symbol.bottom())) continue;
                float overlap = Math.min(rest.pageY() + rest.pageHeight() * .5f, symbol.bottom())
                        - Math.max(rest.pageY() - rest.pageHeight() * .5f, symbol.top());
                if (!(overlap >= rest.pageHeight() * .7f)) continue;
                if (owned != null) {
                    owned = null;
                    break;
                }
                owned = rest;
            }
            if (owned == null) continue;
            if (cleaned == null) cleaned = new ArrayList<>(score.rests());
            cleaned.remove(owned);
        }
        return cleaned == null ? score : score.withRests(List.copyOf(cleaned));
    }

    static ScorePageInterpretation apply(
            ScorePageInterpretation score,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height) {
        return apply(score, words, staffs, gray, width, height, Set.of());
    }

    private static ScorePageInterpretation apply(
            ScorePageInterpretation input,
            List<PlayingTechniqueDetector.Word> words,
            List<PlayingTechniqueDetector.Staff> staffs,
            byte[] gray,
            int width,
            int height,
            Set<PlayingTechniqueDetector.Word> symbols) {
        var score = TempoChangeDetector.recoverAboveLedgerTempos(
                input, words, staffs, gray, width, height);
        var events = new ArrayList<>(score.expressiveEvents());
        for (var feel : ScoreRhythmicFeel.capture(score, words, staffs, width, height))
            if (events.stream().noneMatch(existing -> existing.eventId().equals(feel.eventId())))
                events.add(feel);
        var printedBindings = new HashSet<PrintedBinding>();
        for (var existing : events) {
            var printed =
                    existing.evidence().stream().filter(ScoreExpressionDetector::isColumnEvidence)
                            .toList();
            if (printed.isEmpty()) continue;
            var current = column(existing);
            if (current.isEmpty()) continue;
            String text = "";
            if (existing.kind() == Kind.UNRESOLVED_DIRECTION) {
                var readings =
                        printed.stream().map(e -> bindingText(e.printedText())).distinct().toList();
                if (readings.size() != 1) continue;
                text = readings.get(0);
            }
            var binding =
                    printedBinding(
                            score,
                            current.get(),
                            existing.kind(),
                            existing.strength(),
                            existing.qualifierText(),
                            text);
            if (binding != null) printedBindings.add(binding);
        }
        for (var original : words) {
            var coda = codaCharacterCaption(score, original, staffs, gray, width, height);
            var candidate = coda == null ? original : coda.word();
            boolean shortBowCaption = shortBowCaptionText(candidate.text()) != null;
            boolean rehearsalTempo = ExpressiveDirectionText.rehearsalTempoLabel(candidate.text()) != null;
            var qualified = rehearsalTempo ? localizedRehearsalTempo(candidate, gray, width, height) : candidate;
            if (qualified == null) continue;
            var word = shortBowCaption ? provedShortBowCaption(qualified, gray, width, height) : qualified;
            if (word == null) continue;
            boolean admitted = EnglishCharacterOcrContext.admitted(word);
            boolean stackedCharacter = !admitted
                    && EnglishCharacterOcrContext.stackedCharacter(word.text());
            boolean intensity = !admitted && EnglishCharacterOcrContext.intensity(word.text());
            if (!admitted && !stackedCharacter && !intensity) continue;
            if (coveredPedalWord(word, words) || coveredVibratoWord(word, words)
                    || coveredTempoWord(word, words) || clippedLentoInVerse(word, words)) continue;
            var caption = PrintedTempoCaption.from(word, score);
            String directionText = caption == null ? word.text() : caption.text();
            var directions = shortBowCaption
                    ? List.of(new ExpressiveDirectionText.Direction(
                            Kind.UNRESOLVED_DIRECTION, Strength.UNSPECIFIED, word.text(), ""))
                    : ExpressiveDirectionText.parse(directionText).stream()
                            .filter(d -> KINDS.contains(d.kind()))
                            .toList();
            if (directions.isEmpty() && caption != null)
                directions =
                        List.of(
                                new ExpressiveDirectionText.Direction(
                                        Kind.UNRESOLVED_DIRECTION,
                                        Strength.UNSPECIFIED,
                                        caption.text(),
                                        ""));
            if (directions.isEmpty()) continue;
            boolean release =
                    directions.stream()
                            .allMatch(d -> d.kind() == Kind.BREATH || d.kind() == Kind.CAESURA);
            float x = word.left() * width;
            boolean bowCaption = shortBowCaption || ExpressiveDirectionText.isBowCaption(directionText);
            var captionStaff = bowCaption
                    ? bowCaptionOwner(score, staffs, word, gray, width, height) : null;
            if (bowCaption && captionStaff == null) continue;
            var staff =
                    bowCaption ? captionStaff.staff() : PrintedDirectionStaff.at(
                            staffs,
                            score.measures(),
                            score.notes(),
                            gray,
                            width,
                            height,
                            x,
                            word.top() * height,
                            word.bottom() * height);
            boolean metric = directions.stream().allMatch(d -> d.kind() == Kind.METRIC_MODULATION);
            if (metric && staff == null)
                staff =
                        PrintedDirectionStaff.at(
                                staffs,
                                score.measures(),
                                score.notes(),
                                gray,
                                width,
                                height,
                                word.right() * width,
                                word.top() * height,
                                word.bottom() * height);
            if (metric && staff == null && symbols.contains(word)) {
                for (var candidateStaff : staffs) {
                    if (!PrintedExpressionGlyphs.metricStaffRails(candidateStaff, gray, width, height,
                            Math.round(x), Math.round(word.top() * height), Math.round(word.bottom() * height))
                            && !PrintedExpressionGlyphs.metricStaffRails(candidateStaff, gray, width, height,
                                    Math.round(word.right() * width), Math.round(word.top() * height),
                                    Math.round(word.bottom() * height))) continue;
                    if (staff != null) {
                        staff = null;
                        break;
                    }
                    staff = candidateStaff;
                }
            }
            if (staff == null && symbols.contains(word) && word.text().equals("caesura")) {
                int gx = Math.round((word.left() + word.right()) * width * .5f);
                for (var lane : staffs) {
                    if (!PrintedExpressionGlyphs.caesuraStaffRails(lane, gray, width, height, gx,
                            Math.round(word.top() * height), Math.round(word.bottom() * height))) continue;
                    if (staff != null) {
                        staff = null;
                        break;
                    }
                    staff = lane;
                }
            }
            if (coda != null) staff = coda.staff();
            if (staff == null && word.right() > word.left())
                staff =
                        PrintedDirectionWordStaff.find(
                                staffs, score.measures(), score.notes(), gray, width, height, word);
            if (staff == null && directions.stream().allMatch(d -> switch (d.kind()) {
                case RITARDANDO, RALLENTANDO, RITENUTO, ACCELERANDO, A_TEMPO, TEMPO_MARKING -> true;
                default -> false;
            })) {
                staff = PrintedDirectionWordStaff.nearTempoWord(
                        staffs, score.measures(), score.notes(), gray, width, height, word);
            }
            if (metric && staff != null && symbols.contains(word)) {
                int boundary = terminalMetricBoundary(score.measures(), staff, word, width, height);
                if (boundary < 0) boundary = metricMeterOnset(score, staff, word, width, height);
                if (boundary >= 0) {
                    var direction = directions.get(0);
                    String id = "printed-metric-boundary:" + boundary;
                    if (events.stream().noneMatch(e -> e.eventId().equals(id))) {
                        events.add(new ScoreExpressiveEvent(
                                id, Kind.METRIC_MODULATION,
                                Optional.of(new ScoreAnchor(boundary, 0)), Optional.empty(),
                                Scope.SCORE, staff.index(), staff.count(), Optional.empty(),
                                direction.strength(), direction.qualifierText(),
                                List.of(new Evidence(SYMBOL, 0, word.left(), staff.index(),
                                        staff.count(), direction.printedText()))));
                    }
                    continue;
                }
            }
            ScoreNoteEvent closest =
                    staff == null ? null : closestDirectionNote(score, staff, width, height, x, x);
            boolean metricBoundary = false;
            if (metric && staff != null) {
                float center = (staff.top() + staff.bottom()) * .5f / height;
                int next = -1;
                float boundary = -1;
                int boundaryCount = 0;
                for (int i = 0; i < score.measures().size(); i++) {
                    var region = score.measures().get(i);
                    float bx = region.left() * width;
                    if (center < region.top() - staff.gap() / height
                            || center > region.bottom() + staff.gap() / height) continue;
                    if (bx > x && bx < word.right() * width) {
                        boundaryCount++;
                        if (bx > boundary) {
                            boundary = bx;
                            next = i;
                        }
                    }
                }
                if (boundaryCount > 1) continue;
                if (next >= 0) {
                    metricBoundary = true;
                    closest = null;
                    float first = Float.POSITIVE_INFINITY;
                    for (var note : score.notes())
                        if (!note.crossStaffBeam()
                                && note.measureIndex() == next
                                && note.staffIndex() == staff.index()
                                && note.staffCount() == staff.count()) {
                            var region = score.measures().get(next);
                            float nx =
                                    (region.left()
                                                    + note.positionInMeasure()
                                                            * (region.right() - region.left()))
                                            * width;
                            if (nx >= boundary && nx - boundary <= staff.gap() * 8 && nx < first) {
                                first = nx;
                                closest = note;
                            }
                        }
                }
            }
            if (bowCaption) closest = captionAttack(score, staff, width, height, x, word.right() * width);
            if (metricBoundary && closest == null) continue;
            if (closest == null && staff != null && word.right() > word.left())
                closest =
                        closestDirectionNote(score, staff, width, height, x, word.right() * width);
            if (word.text().strip().equalsIgnoreCase("delicately") && staff != null
                    && word.bottom() * height <= staff.top())
                closest = closestDirectionNote(score, staff, width, height, x, word.right() * width);
            if (stackedCharacter && (closest == null
                    || !ownsStackedCharacter(score, word, words, staff, closest, gray, width, height)))
                continue;
            if (intensity) {
                closest = null;
                for (var lane : staffs) {
                    var owner = intensityOwner(score, word, lane, gray, width, height);
                    if (owner == null) continue;
                    if (closest != null) {
                        closest = null;
                        break;
                    }
                    closest = owner;
                    staff = lane;
                }
                if (closest == null) continue;
            }
            if (word.text().strip().matches("(?i)vib[.]?|vibrato")) {
                var above = highVibratoOwner(score, word, staffs, gray, width, height);
                if (above != null) {
                    closest = above.note();
                    staff = above.staff();
                }
            }
            if (release && symbols.contains(word) && staff != null) {
                var terminal = terminalReleaseNote(score, staff, width, height, word);
                // Preserve an existing owner in this measure. A different measure's
                // nearby attack cannot own a proved terminal release inside this one.
                if (terminal != null
                        && (closest == null || closest.measureIndex() != terminal.measureIndex()))
                    closest = terminal;
            }
            var terminalRest =
                    release && symbols.contains(word) && staff != null
                            ? terminalReleaseRest(score, staff, width, height, word)
                            : null;
            // A proved terminal silent column owns the sign after it, not an earlier
            // sounding note or a closer attack in the following written measure.
            var pedalCaptionRest =
                    PrintedRestDirection.findPedalCaption(score, staffs, word, gray, width, height);
            if (terminalRest != null || pedalCaptionRest != null) closest = null;
            var rest =
                    terminalRest != null
                            ? terminalRest
                            : pedalCaptionRest != null
                                    ? pedalCaptionRest
                                    : closest == null
                                            ? PrintedRestDirection.find(
                                                    score, staffs, word, gray, width, height)
                                            : null;
            if (directions.stream().anyMatch(direction ->
                    direction.kind() == Kind.A_TEMPO || direction.kind() == Kind.RITENUTO
                            || direction.kind() == Kind.TEMPO_MARKING || direction.kind() == Kind.TEMPO_PRIMO)) {
                var restoration = ExpressiveDirectionText.restorationCaption(word.text());
                var sectional = ExpressiveDirectionText.sectionalTempoCaption(word.text());
                boolean tempoPrimo = word.text().strip().matches("(?i)^tempo\\s+(?:i|primo)[.!]?$");
                boolean absoluteTempo = sectional != null && sectional.kind() == Kind.TEMPO_MARKING;
                if (sectional != null || tempoPrimo || restoration != null && (closest == null && rest == null
                        || !word.text().strip().matches("(?i)^a\\s+tempo[.!]?$"))) {
                    var direction = tempoPrimo ? directions.get(0) : sectional != null ? sectional : restoration;
                    var boundary = tempoBarline(score, staffs, word, gray, width, height, absoluteTempo);
                    if (boundary != null && (!rehearsalTempo || startsSystem(score, boundary.measure()))) {
                        int measure = boundary.measure();
                        String id = (sectional == null ? "printed-tempo-restoration:"
                                : "printed-sectional-tempo:") + measure;
                        var owner = boundary.staff();
                        var event = new ScoreExpressiveEvent(
                                id, direction.kind(), Optional.of(new ScoreAnchor(measure, 0)), Optional.empty(),
                                Scope.SCORE, owner.index(), owner.count(), Optional.empty(), direction.strength(),
                                direction.qualifierText(), List.of(new Evidence(
                                        SOURCE, 0, word.left(), owner.index(), owner.count(), word.text())));
                        if (!direction.qualifierText().isEmpty())
                            events.removeIf(previous -> previous.eventId().equals(id)
                                    && previous.qualifierText().isEmpty());
                        if (events.stream().noneMatch(previous -> previous.eventId().equals(id)))
                            events.add(event);
                        continue;
                    }
                }
                if (rehearsalTempo) continue;
                if (absoluteTempo) {
                    // A mid-bar word can own a silent printed entry before the next attack.
                    rest = PrintedRestDirection.tempoRest(score, staffs, word, gray, width, height);
                    if (rest != null) {
                        closest = alignedTempoAttack(score, rest);
                        if (closest != null) rest = null;
                    }
                    else if (!localTempoNote(score, closest, staff, word, width, height)) continue;
                }
            }
            if (closest == null && rest == null) continue;
            if (caption != null
                    && (closest != null ? closest.measureIndex() : rest.measureIndex())
                            != caption.measureIndex()) continue;
            var anchor =
                    closest != null
                            ? new Column(
                                    closest.measureIndex(),
                                    closest.staffIndex(),
                                    closest.staffCount(),
                                    closest.positionInMeasure(),
                                    false)
                            : new Column(
                                    rest.measureIndex(),
                                    rest.staffIndex(),
                                    rest.staffCount(),
                                    rest.positionInMeasure(),
                                    true);
            String target =
                    target(
                            anchor.measure(),
                            anchor.staff(),
                            anchor.count(),
                            anchor.position(),
                            anchor.rest());
            for (var direction : directions) {
                if (anchor.rest()
                        && !PrintedRestDirection.allows(direction.kind())
                        && !PrintedRestDirection.allowsPedalCaption(
                                direction.kind(), direction.printedText())
                        && !(terminalRest != null
                                && (direction.kind() == Kind.BREATH
                                        || direction.kind() == Kind.CAESURA))) continue;
                String id =
                        "printed-expression:"
                                + direction.kind()
                                + ":"
                                + (anchor.rest() ? "rest:" : "")
                                + target.substring(
                                        anchor.rest() ? REST_TARGET.length() : TARGET.length());
                if (direction.kind() == Kind.UNRESOLVED_DIRECTION)
                    id += ":word:" + direction.printedText().trim();
                String eventId = id;
                if (events.stream().anyMatch(e -> e.eventId().equals(eventId))) continue;
                var binding =
                        printedBinding(
                                score,
                                anchor,
                                direction.kind(),
                                direction.strength(),
                                direction.qualifierText(),
                                direction.printedText());
                if (binding != null && !printedBindings.add(binding)) continue;
                events.add(
                        new ScoreExpressiveEvent(
                                id,
                                direction.kind(),
                                Optional.empty(),
                                Optional.empty(),
                                Scope.UNRESOLVED,
                                anchor.staff(),
                                anchor.count(),
                                Optional.of(target),
                                direction.strength(),
                                direction.qualifierText(),
                                expressionEvidence(word, captionStaff, symbols, height,
                                        anchor.staff(), anchor.count(), direction.printedText())));

            }
        }
        return PrintedTempoSpans.apply(
                resolve(score.withExpressiveEvents(events), Float.NaN),
                words,
                staffs,
                gray,
                width,
                height);
    }

    private record Column(int measure, int staff, int count, float position, boolean rest) {}

    private record PrintedBinding(
            int measure,
            int staff,
            int count,
            Kind kind,
            Strength strength,
            String qualifier,
            String text,
            List<Integer> targets) {}

    private static String bindingText(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Repeated OCR coordinates may differ while the existing written-column membership is exact. */
    private static PrintedBinding printedBinding(
            ScorePageInterpretation score,
            Column column,
            Kind kind,
            Strength strength,
            String qualifier,
            String text) {
        if (column.rest()) return null;
        var targets = targetIndicesForColumn(score, column);
        if (targets.isEmpty()) return null;
        return new PrintedBinding(
                column.measure(),
                column.staff(),
                column.count(),
                kind,
                strength,
                qualifier,
                kind == Kind.UNRESOLVED_DIRECTION ? bindingText(text) : "",
                List.copyOf(targets));
    }

    /** A complete character caption above the unique opening attack of a real measure. */
    private static ScoreNoteEvent intensityOwner(
            ScorePageInterpretation score, PlayingTechniqueDetector.Word word,
            PlayingTechniqueDetector.Staff staff, byte[] gray, int width, int height) {
        if (staff == null || gray == null) return null;
        float gap = staff.gap(), left = word.left() * width;
        float clearance = staff.top() - word.bottom() * height;
        float tall = (word.bottom() - word.top()) * height;
        if (gap < 6 || clearance < 0 || clearance > gap * 4
                || tall < gap * .9f || tall > gap * 3.8f
                || !ScoreDynamicsDetector.containsInk(word, gray, width, height)) return null;
        float staffCenter = (staff.top() + staff.bottom()) * .5f / height;
        ScoreNoteEvent owner = null;
        for (int m = 0; m < score.measures().size(); m++) {
            var bar = score.measures().get(m);
            float inset = left - bar.left() * width;
            if (staffCenter < bar.top() || staffCenter > bar.bottom()
                    || inset < -gap * 1.35f || inset > gap * 2) continue;
            ScoreNoteEvent first = null;
            for (var n : score.notes())
                if (n.measureIndex() == m && n.staffIndex() == staff.index()
                        && n.staffCount() == staff.count() && !n.crossStaffBeam()
                        && (n.articulations() & NoteOrnament.GRACE) == 0
                        && (first == null || n.positionInMeasure() < first.positionInMeasure()))
                    first = n;
            if (first == null) continue;
            float head = (bar.left() + first.positionInMeasure() * (bar.right() - bar.left())) * width;
            if (head < left || head - left > gap * 3.3f || head > word.right() * width) continue;
            if (owner != null) return null;
            owner = first;
        }
        return owner;
    }

    private record CodaCharacter(PlayingTechniqueDetector.Word word, PlayingTechniqueDetector.Staff staff) {}

    /** A merged navigation OCR line can also contain a separate incoming character caption. */
    private static CodaCharacter codaCharacterCaption(
            ScorePageInterpretation score, PlayingTechniqueDetector.Word word,
            List<PlayingTechniqueDetector.Staff> staffs, byte[] gray, int width, int height) {
        if (gray == null || !word.text().strip().matches(
                "(?i)d[.]?\\s*s[.]?\\s+al\\s+coda\\s+with\\s+power[!]"))
            return null;
        CodaCharacter found = null;
        for (var direction : score.playbackDirections()) {
            int m = direction.measureBoundary();
            if (direction.kind() != ScorePlaybackDirection.Kind.CODA
                    || m <= 0 || m >= score.measures().size()
                    || score.playbackDirections().stream().noneMatch(d ->
                            d.measureBoundary() == m
                                    && d.kind() == ScorePlaybackDirection.Kind.DAL_SEGNO_AL_CODA))
                continue;
            var bar = score.measures().get(m);
            if (bar.left() <= word.left() || bar.left() >= word.right()) continue;
            for (var staff : staffs) {
                float center = (staff.top() + staff.bottom()) * .5f / height;
                if (center < bar.top() || center > bar.bottom()
                        || word.top() * height > staff.top() - staff.gap()
                        || word.bottom() * height > staff.top() + staff.gap() * 1.6f) continue;
                float left = bar.left() + staff.gap() * 1.6f / width;
                float top = Math.max(word.top(), (staff.top() - staff.gap() * 4.1f) / height);
                float bottom = Math.min(word.bottom(), (staff.top() - staff.gap() * .8f) / height);
                if (left >= word.right() || bottom <= top) continue;
                var suffix = new PlayingTechniqueDetector.Word(
                        "with power!", left, top, word.right(), bottom);
                if (!ScoreDynamicsDetector.containsInk(suffix, gray, width, height)) continue;
                if (found != null) return null;
                found = new CodaCharacter(suffix, staff);
            }
        }
        return found;
    }
    private record HighVibratoOwner(ScoreNoteEvent note, PlayingTechniqueDetector.Staff staff) {}

    private static HighVibratoOwner highVibratoOwner(
            ScorePageInterpretation score, PlayingTechniqueDetector.Word word,
            List<PlayingTechniqueDetector.Staff> staffs, byte[] gray, int width, int height) {
        if (gray == null || !ScoreDynamicsDetector.containsInk(word, gray, width, height)) return null;
        HighVibratoOwner owner = null;
        float left = word.left() * width, right = word.right() * width;
        for (var staff : staffs) {
            float gap = staff.gap();
            float center = (staff.top() + staff.bottom()) * .5f / height;
            var note = closestDirectionNote(score, staff, width, height, left, right);
            if (note == null || note.tiedFromPrevious() || note.crossStaffBeam()) continue;
            var bar = score.measures().get(note.measureIndex());
            if (center < bar.top() || center > bar.bottom()) continue;
            float headX = (bar.left() + note.positionInMeasure() * (bar.right() - bar.left())) * width;
            float headY = note.pageY() * height;
            float clearance = headY - word.bottom() * height;
            if (headX < left || headX > right || staff.top() - headY < gap * 2
                    || clearance < gap * .15f || clearance > gap * 1.35f) continue;
            if (owner != null) return null;
            owner = new HighVibratoOwner(note, staff);
        }
        return owner;
    }
    private static boolean coveredVibratoWord(
            PlayingTechniqueDetector.Word word, List<PlayingTechniqueDetector.Word> words) {
        if (!word.text().strip().matches("(?i)vib\\.?|vibrato")) return false;
        float height = word.bottom() - word.top();
        if (!(height > 0) || !(word.right() > word.left())) return false;
        for (var other : words) {
            if (!other.text().strip().matches("(?i)(?:vib\\.?|vibrato)\\s*\\(abrupt\\s+cut[- ]off\\)")
                    || word.left() < other.left() || word.right() > other.right()) continue;
            float overlap = Math.min(word.bottom(), other.bottom()) - Math.max(word.top(), other.top());
            if (overlap >= .8f * height) return true;
        }
        return false;
    }

    private static boolean ownsStackedCharacter(
            ScorePageInterpretation score, PlayingTechniqueDetector.Word word,
            List<PlayingTechniqueDetector.Word> words, PlayingTechniqueDetector.Staff staff,
            ScoreNoteEvent owner, byte[] gray, int width, int height) {
        if (gray == null || word.bottom() * height >= staff.top()
                || !ScoreDynamicsDetector.containsInk(word, gray, width, height)) return false;
        PlayingTechniqueDetector.Word matched = null;
        for (var sibling : words) {
            if (!EnglishCharacterOcrContext.stackedSibling(sibling.text())
                    || sibling.top() <= word.top() || sibling.bottom() <= word.bottom()
                    || sibling.bottom() * height >= staff.top()
                    || Math.abs(sibling.top() - word.bottom()) * height > staff.gap()
                    || Math.abs(sibling.left() - word.left()) * width > staff.gap()
                    || !ScoreDynamicsDetector.containsInk(sibling, gray, width, height)) continue;
            float left = sibling.left() * width;
            var siblingOwner = closestDirectionNote(score, staff, width, height, left, left);
            if (siblingOwner == null)
                siblingOwner = closestDirectionNote(score, staff, width, height,
                        left, sibling.right() * width);
            if (!owner.equals(siblingOwner)) continue;
            // Native intake can report the same physical OCR line in two word lists.
            if (matched != null && !matched.equals(sibling)) return false;
            matched = sibling;
        }
        return matched != null;
    }

    private static String shortBowCaptionText(String token) {
        if (ExpressiveDirectionText.isShortBowCaption(token)) return token;
        String text = token.strip();
        if (!text.endsWith("1")) return null;
        String prefix = text.substring(0, text.length() - 1).stripTrailing();
        return ExpressiveDirectionText.isShortBowCaption(prefix) ? prefix : null;
    }

    /** Padded OCR boxes can include a staff line or neighboring note below the caption. */
    private static PlayingTechniqueDetector.Word provedShortBowCaption(
            PlayingTechniqueDetector.Word word, byte[] ink, int width, int height) {
        String literal = shortBowCaptionText(word.text());
        if (literal == null) return null;
        var proof = provedShortBowCaption(word, literal, ink, width, height, 165);
        // The dark contour can separate a descender from a neighboring beam's antialias fringe.
        return proof != null ? proof : provedShortBowCaption(word, literal, ink, width, height, 135);
    }

    private static PlayingTechniqueDetector.Word provedShortBowCaption(
            PlayingTechniqueDetector.Word word, String literal, byte[] ink, int width, int height, int threshold) {
        if (ink == null || ink.length != (long) width * height) return null;
        int left = Math.round(word.left() * width), right = Math.round(word.right() * width);
        int top = Math.round(word.top() * height), bottom = Math.round(word.bottom() * height);
        int w = right - left + 1, h = bottom - top + 1;
        if (left < 0 || right >= width || top < 0 || bottom >= height
                || w < 7 || h < 7 || (long) w * h > 15000) return null;
        var inkOwners = RawNumeralComponents.captureLongWindow(ink, width, height, left, top, right, bottom, threshold);
        var glyphs = inkOwners.components().stream()
                .filter(c -> c.left > left && c.right < right && c.top > top && c.bottom < bottom && c.size() >= 5)
                .toList();
        int expectedLetters = literal.strip().equalsIgnoreCase("m.") ? 1 : 2;
        boolean mergedFingering = !literal.equals(word.text());
        PlayingTechniqueDetector.Word result = null;
        for (var dot : glyphs) {
            int dotWidth = dot.right - dot.left + 1, dotHeight = dot.bottom - dot.top + 1;
            if (dot.left < left + w * .38f || dotWidth < 2 || dotHeight < 2
                    || dotWidth > h * .23f || dotHeight > h * .23f
                    || dotWidth > dotHeight * 1.8f || dotHeight > dotWidth * 1.8f
                    || dot.size() < dotWidth * dotHeight * .55f) continue;
            var letters = glyphs.stream().filter(c -> c.right < dot.left
                    && c.bottom - c.top + 1 >= h * .29f
                    && c.right - c.left + 1 >= h * .11f
                    && c.top <= dot.top - .15f * h
                    && c.bottom >= dot.top - .09f * h && c.bottom <= dot.bottom + .39f * h).toList();
            if (letters.size() != expectedLetters) continue;
            int baseline = letters.stream().mapToInt(c -> c.bottom).min().orElseThrow();
            if (Math.abs((dot.top + dot.bottom) * .5f - baseline) > .11f * h) continue;
            int first = letters.stream().mapToInt(c -> c.left).min().orElseThrow();
            int lastLetter = letters.stream().mapToInt(c -> c.right).max().orElseThrow();
            int head = letters.stream().mapToInt(c -> c.top).min().orElseThrow();
            int foot = Math.max(dot.bottom, letters.stream().mapToInt(c -> c.bottom).max().orElseThrow());
            var trailing = glyphs.stream().filter(c -> c.left > dot.right
                    && c.top <= dot.top && c.bottom >= head && c.bottom - c.top >= .28f * h).toList();
            if (dot.left - lastLetter > .31f * h || dot.right - first < w * .35f
                    || (mergedFingering ? trailing.size() != 1
                            || !separateTrailingOne(ink, width, height, dot, trailing.get(0), h)
                            : !trailing.isEmpty())) continue;
            if (result != null) return null;
            result = new PlayingTechniqueDetector.Word(literal, first / (float) width, head / (float) height,
                    (dot.right + 1) / (float) width, (foot + 1) / (float) height, word.admission());
        }
        return result;
    }

    /** A combined OCR token only contributes the words if the following one has its own ink. */
    private static boolean separateTrailingOne(byte[] ink, int width, int height, RawNumeralComponents.Component dot,
            RawNumeralComponents.Component digit, int tokenHeight) {
        float gap = digit.left - dot.right;
        if (gap < tokenHeight * .10f || gap > tokenHeight * .60f
                || Math.abs(digit.bottom - dot.bottom) > tokenHeight * .14f) return false;
        if (UnnumberedTripletInk.isUnambiguousOne(ink, width, digit)) return true;
        var reading = JoinedDigitTemplates.inspect(ink, width, height, new BitSet(),
                digit.left, digit.top, digit.right, digit.bottom, () -> {});
        return reading != null && reading.digit() == 1 && reading.best() >= .64f
                && reading.best() - reading.runner() >= .09f;
    }

    private static List<Evidence> expressionEvidence(PlayingTechniqueDetector.Word word,
            BowCaptionStaff caption, Set<PlayingTechniqueDetector.Word> symbols, int height,
            int staff, int count, String text) {
        var primary = new Evidence(symbols.contains(word) ? SYMBOL
                : caption != null && caption.ledgerSupported() ? LEDGER_CAPTION : SOURCE,
                0, word.left(), staff, count, text);
        if (caption == null || !"segue".equalsIgnoreCase(text)
                || word.top() * height < caption.staff().bottom() + .25f * caption.staff().gap())
            return List.of(primary);
        return List.of(primary, new Evidence("printed-expression-below-bow-caption",
                primary.pageIndex(), primary.visualX(), staff, count, text));
    }

    private record BowCaptionStaff(PlayingTechniqueDetector.Staff staff, boolean ledgerSupported) {}

    private static BowCaptionStaff bowCaptionOwner(
            ScorePageInterpretation score, List<PlayingTechniqueDetector.Staff> staffs,
            PlayingTechniqueDetector.Word word, byte[] gray, int width, int height) {
        float x = word.left() * width, right = word.right() * width;
        float top = word.top() * height, bottom = word.bottom() * height;
        if (gray == null || !ScoreDynamicsDetector.containsInk(word, gray, width, height)) return null;
        BowCaptionStaff owner = null;
        float best = Float.POSITIVE_INFINITY;
        for (var staff : staffs) {
            float gap = staff.gap();
            float clearance = bottom <= staff.top() ? staff.top() - bottom
                    : top >= staff.bottom() ? top - staff.bottom() : -1;
            boolean shortCaption = ExpressiveDirectionText.isShortBowCaption(word.text());
            float minimumClearance = shortCaption ? .04f : .25f;
            if (gap < 6 || clearance < gap * minimumClearance
                    || top >= staff.bottom() && clearance > gap * 5
                    || bottom - top > gap * 3.5f || right - x > gap * 25) continue;
            var nearbyAttack = captionAttack(score, staff, width, height, x, right);
            if (bottom < staff.top() && nearbyAttack != null)
                nearbyAttack = upperCaptionHead(score, nearbyAttack, staff.gap(), width);
            if (shortCaption && top >= staff.bottom()
                    && (nearbyAttack == null || top - nearbyAttack.pageY() * height > gap * 5)) continue;
            boolean ledgerCaption = nearbyAttack != null && bottom < staff.top()
                    && staff.top() - nearbyAttack.pageY() * height >= gap * 2
                    && nearbyAttack.pageY() * height - bottom <= gap * 3;
            // A rising ledger-note run can bring the later heads close to a high caption.
            // Use them to establish the staff, while retaining the caption's leftmost attack.
            float ledgerDistance = !ledgerCaption && clearance > gap * 5 && nearbyAttack != null
                    && bottom < staff.top() ? ledgerCaptionDistance(score, staff, nearbyAttack,
                            width, height, x, right, bottom) : Float.POSITIVE_INFINITY;
            if (clearance > gap * 5 && !ledgerCaption && !Float.isFinite(ledgerDistance)) continue;
            ledgerCaption |= Float.isFinite(ledgerDistance);
            boolean directlyAbove = shortCaption && nearbyAttack != null && bottom < staff.top();
            if (directlyAbove) {
                var measure = score.measures().get(nearbyAttack.measureIndex());
                float noteX = (measure.left() + nearbyAttack.positionInMeasure()
                        * (measure.right() - measure.left())) * width;
                directlyAbove = noteX >= x && noteX <= right;
            }
            float firstSample = Float.NaN;
            PlayingTechniqueDetector.Staff proof = null;
            boolean verifiedPair = false;
            float[] samples = {0, 1, 2, 3, 4, 5, 6, 7, 8, -1, -2, .5f, 1.5f, 2.5f,
                    3.5f, 4.5f, 5.5f, 6.5f, 7.5f, -3, -3.5f, -4, -4.5f, -5,
                    8.5f, 9, 9.5f, 10, 10.5f, 11, 11.5f, 12, 12.5f, 13, 13.5f, 14, 14.5f, 15};
            for (float delta : samples) {
                if (!directlyAbove && (!ledgerCaption && (delta < -2 || delta > 8) || delta > 13)) break;
                float sample = x + gap * delta;
                var local = PrintedDirectionStaff.local(
                        staff, score.measures(), score.notes(), gray, width, height, sample);
                if (local == null) continue;
                if (proof == null) {
                    proof = local;
                    firstSample = sample;
                } else if (Math.abs(sample - firstSample) >= gap * .95f
                        && Math.abs(proof.bottom() - local.bottom()) <= gap * .3f
                        && Math.abs(proof.gap() - local.gap()) <= gap * .1f) {
                    var attack = captionAttack(score, proof, width, height, x, right);
                    if (attack == null) break;
                    if (bottom < proof.top()) attack = upperCaptionHead(score, attack, proof.gap(), width);
                    verifiedPair = true;
                    float distance = Math.min(clearance / gap, ledgerDistance);
                    if (bottom < proof.top() && attack.pageY() * height < proof.top()) {
                        var measure = score.measures().get(attack.measureIndex());
                        float attackX = (measure.left() + attack.positionInMeasure()
                                * (measure.right() - measure.left())) * width;
                        float headClearance = attack.pageY() * height - bottom;
                        // Ledger-line heads can carry a caption well above the top rule.
                        if (attackX >= x && attackX <= right
                                && headClearance >= gap * .25f && headClearance <= gap * 3)
                            distance = Math.min(distance, headClearance / gap);
                    }
                    if (distance <= 5 && distance < best) {
                        best = distance;
                        owner = new BowCaptionStaff(proof, Float.isFinite(ledgerDistance));
                    }
                    break;
                }
            }
            if (!verifiedPair && ledgerDistance < best) {
                var distant = surroundingCaptionStaff(score, staff, gray, width, height, x, right);
                if (distant != null) {
                    best = ledgerDistance;
                    owner = new BowCaptionStaff(distant, true);
                }
            }
            if (!verifiedPair && directlyAbove && clearance / gap < best) {
                float headClearance = nearbyAttack.pageY() * height - bottom;
                if (headClearance >= gap * .25f && headClearance <= gap * 5) {
                    var distant = surroundingCaptionStaff(score, staff, gray, width, height, x, right);
                    if (distant != null) {
                        best = clearance / gap;
                        owner = new BowCaptionStaff(distant, false);
                    }
                }
            }
        }
        return owner;
    }

    private static float ledgerCaptionDistance(ScorePageInterpretation score,
            PlayingTechniqueDetector.Staff staff, ScoreNoteEvent first, int width, int height,
            float left, float right, float lowerEdge) {
        float gap = staff.gap();
        var measure = score.measures().get(first.measureIndex());
        float firstX = (measure.left() + first.positionInMeasure() * (measure.right() - measure.left())) * width;
        if (firstX < left || firstX > left + gap * 2.5f) return Float.POSITIVE_INFINITY;
        float distance = Float.POSITIVE_INFINITY;
        var supportColumns = new java.util.HashSet<Integer>();
        for (var note : score.notes()) {
            if (note.measureIndex() != first.measureIndex() || note.staffIndex() != staff.index()
                    || note.staffCount() != staff.count() || note.crossStaffBeam()
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED || note.visualSize() != ScoreNoteEvent.VisualSize.FULL
                    || (note.articulations() & NoteOrnament.GRACE) != 0) continue;
            float x = (measure.left() + note.positionInMeasure() * (measure.right() - measure.left())) * width;
            float y = note.pageY() * height;
            float clearance = y - lowerEdge;
            if (x <= firstX || x > right || staff.top() - y < gap * 2
                    || clearance < gap * .25f || clearance > gap * 3.3f) continue;
            supportColumns.add(Math.round(x));
            distance = Math.min(distance, clearance / gap);
        }
        return supportColumns.size() >= 2 ? distance : Float.POSITIVE_INFINITY;
    }

    /** Dense notes can obscure the rules around a caption; bracket its attack with agreeing staff ink. */
    private static PlayingTechniqueDetector.Staff surroundingCaptionStaff(
            ScorePageInterpretation score, PlayingTechniqueDetector.Staff staff, byte[] gray,
            int width, int height, float x, float right) {
        float gap = staff.gap();
        PlayingTechniqueDetector.Staff left = null;
        for (float delta = 1; delta <= 35; delta += .5f) {
            left = PrintedDirectionStaff.local(staff, score.measures(), score.notes(), gray, width, height,
                    x - gap * delta);
            if (left != null) break;
        }
        if (left == null) return null;
        for (float delta = 1; delta <= 35; delta += .5f) {
            float sample = x + gap * delta;
            if (sample < right) continue;
            var other = PrintedDirectionStaff.local(staff, score.measures(), score.notes(), gray, width, height,
                    sample);
            if (other != null && Math.abs(left.bottom() - other.bottom()) <= gap * .30f
                    && Math.abs(left.gap() - other.gap()) <= gap * .10f)
                return new PlayingTechniqueDetector.Staff((left.top() + other.top()) * .5f,
                        (left.bottom() + other.bottom()) * .5f, (left.gap() + other.gap()) * .5f,
                        staff.index(), staff.count());
        }
        return null;
    }

    /** Height evidence for a caption above a chord belongs to that chord's upper head. */
    private static ScoreNoteEvent upperCaptionHead(
            ScorePageInterpretation score, ScoreNoteEvent attack, float gap, int width) {
        var region = score.measures().get(attack.measureIndex());
        float advance = (region.right() - region.left()) * width;
        var highest = attack;
        for (var member : score.notes()) {
            if (member.measureIndex() != attack.measureIndex() || member.staffIndex() != attack.staffIndex()
                    || member.staffCount() != attack.staffCount() || member.crossStaffBeam()
                    || member.kind() != ScoreNoteEvent.Kind.PITCHED
                    || (member.articulations() & NoteOrnament.GRACE) != 0
                    || Math.abs(member.positionInMeasure() - attack.positionInMeasure()) * advance > .16f * gap)
                continue;
            if (member.pageY() < highest.pageY()) highest = member;
        }
        return highest;
    }

    private static ScoreNoteEvent captionAttack(
            ScorePageInterpretation score, PlayingTechniqueDetector.Staff staff,
            int width, int height, float left, float right) {
        float center = (staff.top() + staff.bottom()) * .5f / height;
        ScoreNoteEvent first = null;
        float firstX = Float.POSITIVE_INFINITY;
        for (var note : score.notes()) {
            if (note.staffIndex() != staff.index() || note.staffCount() != staff.count()
                    || note.crossStaffBeam() || (note.articulations() & NoteOrnament.GRACE) != 0)
                continue;
            var measure = score.measures().get(note.measureIndex());
            if (center < measure.top() || center > measure.bottom()) continue;
            float x = (measure.left() + note.positionInMeasure()
                    * (measure.right() - measure.left())) * width;
            if (x >= left && x <= right && x < firstX) {
                firstX = x;
                first = note;
            }
        }
        return first != null ? first : closestDirectionNote(score, staff, width, height, left, left);
    }

    /** A proved equation straddling the last barline in its system belongs to the next bar. */
    private static int metricMeterOnset(
            ScorePageInterpretation score,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word equation, int width, int height) {
        float x = equation.left() * width, right = equation.right() * width;
        float center = (staff.top() + staff.bottom()) * .5f;
        float gap = staff.gap();
        if (equation.bottom() * height > staff.top() - gap * .35f) return -1;
        int selected = -1;
        for (var change : score.meterChanges()) {
            int index = change.measureIndex();
            if (index < 0 || index >= score.measures().size()) continue;
            var region = score.measures().get(index);
            if (center < region.top() * height - gap || center > region.bottom() * height + gap) continue;
            float left = region.left() * width;
            if (x < left - gap * 1.5f || x > left + gap * 4.5f || right < left
                    || right > region.right() * width + gap * .5f) continue;
            if (selected >= 0 && selected != index) return -1;
            selected = index;
        }
        return selected;
    }

    private static int terminalMetricBoundary(
            List<MeasureRegion> measures,
            PlayingTechniqueDetector.Staff staff,
            PlayingTechniqueDetector.Word word,
            int width,
            int height) {
        float center = (staff.top() + staff.bottom()) * .5f / height;
        float margin = staff.gap() / height;
        int last = -1;
        for (int i = 0; i < measures.size(); i++) {
            var bar = measures.get(i);
            if (center < bar.top() - margin || center > bar.bottom() + margin) continue;
            if (last < 0 || bar.right() >= measures.get(last).right()) last = i;
        }
        if (last < 0 || word.bottom() * height >= staff.top()) return -1;
        float edge = measures.get(last).right();
        return word.left() < edge && edge < word.right() ? last + 1 : -1;
    }

    private static ScoreNoteEvent closestDirectionNote(
            ScorePageInterpretation score,
            PlayingTechniqueDetector.Staff staff,
            int width,
            int height,
            float left,
            float right) {
        ScoreNoteEvent closest = null;
        float distance = Float.MAX_VALUE;
        for (var note : score.notes()) {
            if (note.staffIndex() != staff.index() || note.staffCount() != staff.count()) continue;
            var region = score.measures().get(note.measureIndex());
            float center = (staff.top() + staff.bottom()) * .5f / height;
            if (center < region.top() - staff.gap() / height
                    || center > region.bottom() + staff.gap() / height) continue;
            float nx =
                    (region.left() + note.positionInMeasure() * (region.right() - region.left()))
                            * width;
            float d = Math.max(0, Math.max(left - nx, nx - right));
            if (d < distance && d <= staff.gap() * 3) {
                closest = note;
                distance = d;
            }
        }
        return closest;
    }

    /** A raster-proved isolated release sign belongs to its containing written measure. */
    private static ScoreNoteEvent terminalReleaseNote(
            ScorePageInterpretation score,
            PlayingTechniqueDetector.Staff staff,
            int width,
            int height,
            PlayingTechniqueDetector.Word word) {
        if (width <= 0
                || height <= 0
                || !Float.isFinite(staff.top())
                || !Float.isFinite(staff.bottom())
                || !Float.isFinite(staff.gap())
                || staff.gap() <= 0
                || staff.top() >= staff.bottom()
                || staff.top() < 0
                || staff.bottom() > height
                || staff.count() <= 0
                || staff.index() < 0
                || staff.index() >= staff.count()
                || !Float.isFinite(word.left())
                || !Float.isFinite(word.right())
                || !Float.isFinite(word.top())
                || !Float.isFinite(word.bottom())
                || word.left() < 0
                || word.right() > 1
                || word.left() >= word.right()
                || word.top() < 0
                || word.bottom() > 1
                || word.top() >= word.bottom()) return null;
        float center = (staff.top() + staff.bottom()) * .5f / height;
        float margin = staff.gap() / height;
        if (!Float.isFinite(center) || !Float.isFinite(margin)) return null;
        int measure = -1;
        for (int i = 0; i < score.measures().size(); i++) {
            var region = score.measures().get(i);
            if (!Float.isFinite(region.left())
                    || !Float.isFinite(region.right())
                    || !Float.isFinite(region.top())
                    || !Float.isFinite(region.bottom())
                    || region.left() < 0
                    || region.right() > 1
                    || region.left() >= region.right()
                    || region.top() < 0
                    || region.bottom() > 1
                    || region.top() >= region.bottom()) return null;
            if (center < region.top() - margin
                    || center > region.bottom() + margin
                    || word.left() < region.left()
                    || word.right() > region.right()) continue;
            if (measure >= 0) return null;
            measure = i;
        }
        if (measure < 0) return null;
        var region = score.measures().get(measure);
        ScoreNoteEvent terminal = null;
        for (var note : score.notes()) {
            if (note.measureIndex() != measure) continue;
            if (note.crossStaffBeam()
                    || note.staffCount() != staff.count()
                    || note.staffIndex() < 0
                    || note.staffIndex() >= staff.count()) return null;
            if (note.staffIndex() != staff.index()) continue;
            float position = note.positionInMeasure();
            if (!Float.isFinite(position)
                    || position < 0
                    || position > 1
                    || !Float.isFinite(note.pageY())
                    || note.pageY() < 0
                    || note.pageY() > 1) return null;
            float nx = (region.left() + position * (region.right() - region.left())) * width;
            // Every column in this lane must precede the glyph, not just a favorable note.
            if (!Float.isFinite(nx) || nx >= word.left() * width) return null;
            if (terminal == null || position > terminal.positionInMeasure()) terminal = note;
        }
        if (terminal == null) return null;
        // Use the resolver's existing column tolerance; all notes it will select
        // must be pitched. Its unchanged common-onset/common-release proof follows.
        for (var note : score.notes())
            if (note.measureIndex() == measure
                    && note.staffIndex() == staff.index()
                    && note.staffCount() == staff.count()
                    && Math.abs(note.positionInMeasure() - terminal.positionInMeasure()) <= .018f
                    && note.kind() != ScoreNoteEvent.Kind.PITCHED) return null;
        for (var rest : score.rests()) {
            if (rest.measureIndex() != measure) continue;
            if (rest.staffCount() != staff.count()
                    || rest.staffIndex() < 0
                    || rest.staffIndex() >= staff.count()) return null;
            if (rest.staffIndex() != staff.index()) continue;
            if (!Float.isFinite(rest.positionInMeasure())
                    || rest.positionInMeasure() < 0
                    || rest.positionInMeasure() > 1
                    || !Float.isFinite(rest.pageY())
                    || rest.pageY() < 0
                    || rest.pageY() > 1
                    || !Double.isFinite(rest.durationBeats())
                    || rest.durationBeats() <= 0
                    || rest.kind() == ScoreRestEvent.Kind.FULL_MEASURE
                    || rest.positionInMeasure() >= terminal.positionInMeasure()
                    || Math.abs(rest.positionInMeasure() - terminal.positionInMeasure()) <= .018f)
                return null;
        }
        return terminal;
    }

    /** A literal terminal rest is an explicit silent owner; no rest is inferred from a note. */
    private static ScoreRestEvent terminalReleaseRest(
            ScorePageInterpretation score,
            PlayingTechniqueDetector.Staff staff,
            int width,
            int height,
            PlayingTechniqueDetector.Word word) {
        if (width <= 0
                || height <= 0
                || !Float.isFinite(staff.top())
                || !Float.isFinite(staff.bottom())
                || !Float.isFinite(staff.gap())
                || staff.gap() <= 0
                || staff.top() >= staff.bottom()
                || staff.top() < 0
                || staff.bottom() > height
                || staff.count() <= 0
                || staff.index() < 0
                || staff.index() >= staff.count()
                || !Float.isFinite(word.left())
                || !Float.isFinite(word.right())
                || !Float.isFinite(word.top())
                || !Float.isFinite(word.bottom())
                || word.left() < 0
                || word.right() > 1
                || word.left() >= word.right()
                || word.top() < 0
                || word.bottom() > 1
                || word.top() >= word.bottom()) return null;
        float center = (staff.top() + staff.bottom()) * .5f / height;
        float margin = staff.gap() / height;
        if (!Float.isFinite(center) || !Float.isFinite(margin)) return null;
        int measure = -1;
        for (int i = 0; i < score.measures().size(); i++) {
            var region = score.measures().get(i);
            if (!Float.isFinite(region.left())
                    || !Float.isFinite(region.right())
                    || !Float.isFinite(region.top())
                    || !Float.isFinite(region.bottom())
                    || region.left() < 0
                    || region.right() > 1
                    || region.left() >= region.right()
                    || region.top() < 0
                    || region.bottom() > 1
                    || region.top() >= region.bottom()) return null;
            if (center < region.top() - margin
                    || center > region.bottom() + margin
                    || word.left() < region.left()
                    || word.right() > region.right()) continue;
            if (measure >= 0) return null;
            measure = i;
        }
        if (measure < 0) return null;
        var region = score.measures().get(measure);
        ScoreRestEvent terminal = null;
        for (var rest : score.rests()) {
            if (rest.measureIndex() != measure) continue;
            if (rest.staffCount() != staff.count()
                    || rest.staffIndex() < 0
                    || rest.staffIndex() >= staff.count()) return null;
            if (rest.staffIndex() != staff.index()) continue;
            float position = rest.positionInMeasure();
            if (!Float.isFinite(position)
                    || position < 0
                    || position > 1
                    || !Float.isFinite(rest.pageY())
                    || rest.pageY() < 0
                    || rest.pageY() > 1
                    || Math.abs(rest.pageY() * height - (staff.top() + staff.bottom()) * .5f)
                            > staff.gap() * 2.5f
                    || !Float.isFinite(rest.pageHeight())
                    || rest.pageHeight() <= 0
                    || rest.pageHeight() > 1
                    || !Double.isFinite(rest.durationBeats())
                    || rest.durationBeats() <= 0
                    || rest.isFullMeasure()) return null;
            float rx = (region.left() + position * (region.right() - region.left())) * width;
            if (!Float.isFinite(rx) || rx >= word.left() * width) return null;
            if (terminal == null || position > terminal.positionInMeasure()) terminal = rest;
        }
        if (terminal == null) return null;
        int owners = 0;
        for (var rest : score.rests())
            if (rest.measureIndex() == measure
                    && rest.staffIndex() == staff.index()
                    && rest.staffCount() == staff.count()
                    && Math.abs(rest.positionInMeasure() - terminal.positionInMeasure()) <= .018f)
                owners++;
        if (owners != 1) return null;
        for (var note : score.notes()) {
            if (note.measureIndex() != measure) continue;
            if (note.crossStaffBeam()
                    || note.staffCount() != staff.count()
                    || note.staffIndex() < 0
                    || note.staffIndex() >= staff.count()) return null;
            if (note.staffIndex() != staff.index()) continue;
            float position = note.positionInMeasure();
            if (!Float.isFinite(position)
                    || position < 0
                    || position > 1
                    || !Float.isFinite(note.pageY())
                    || note.pageY() < 0
                    || note.pageY() > 1
                    || note.kind() != ScoreNoteEvent.Kind.PITCHED
                    || position >= terminal.positionInMeasure()
                    || Math.abs(position - terminal.positionInMeasure()) <= .018f) return null;
        }
        return terminal;
    }

    /** Re-use exact silent-slot accounting; a spatially selected rest supplies no guessed beat. */
    private static double restRelease(
            ScorePageInterpretation score, Column column, float beats, double start) {
        if (!Double.isFinite(start) || start < 0) return Double.NaN;
        ScoreRestEvent target = null;
        for (var rest : score.rests())
            if (rest.measureIndex() == column.measure()
                    && rest.staffIndex() == column.staff()
                    && rest.staffCount() == column.count()
                    && Math.abs(rest.positionInMeasure() - column.position()) <= .018f) {
                if (target != null || rest.isFullMeasure()) return Double.NaN;
                target = rest;
            }
        if (target == null) return Double.NaN;
        double duration = target.resolvedDurationBeats(beats), end = start + duration;
        if (!Double.isFinite(duration)
                || duration <= 0
                || !Double.isFinite(end)
                || end <= start
                || end > beats) return Double.NaN;
        return end;
    }

    private static String target(int measure, int staff, int count, float position, boolean rest) {
        return (rest ? REST_TARGET : TARGET)
                + measure
                + ":"
                + staff
                + ":"
                + count
                + ":"
                + Float.floatToIntBits(position);
    }

    private static boolean isColumnEvidence(Evidence evidence) {
        String source = evidence.sourceId();
        return source.equals(SOURCE) || source.equals(LEDGER_CAPTION) || source.equals(SYMBOL);
    }

    private static Optional<Column> column(ScoreExpressiveEvent event) {
        if (!KINDS.contains(event.kind())
                || event.evidence().stream().noneMatch(ScoreExpressionDetector::isColumnEvidence)
                        && !PrintedCueSize.owns(event)
                || event.targetEventId().isEmpty()
                || !(event.targetEventId().get().startsWith(TARGET)
                        || event.targetEventId().get().startsWith(REST_TARGET)))
            return Optional.empty();
        try {
            boolean rest = event.targetEventId().get().startsWith(REST_TARGET);
            var fields =
                    event.targetEventId()
                            .get()
                            .substring(rest ? REST_TARGET.length() : TARGET.length())
                            .split(":");
            if (fields.length != 4) return Optional.empty();
            var value =
                    new Column(
                            Integer.parseInt(fields[0]),
                            Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2]),
                            Float.intBitsToFloat(Integer.parseInt(fields[3])),
                            rest);
            if (value.measure() < 0
                    || value.staff() != event.staffIndex()
                    || value.count() != event.staffCount()
                    || !Float.isFinite(value.position())
                    || value.position() < 0
                    || value.position() > 1) return Optional.empty();
            return Optional.of(value);
        } catch (NumberFormatException error) {
            return Optional.empty();
        }
    }

    static OptionalInt measureOf(ScoreExpressiveEvent event) {
        var c = column(event);
        return c.isPresent() ? OptionalInt.of(c.get().measure()) : OptionalInt.empty();
    }

    static boolean owns(ScoreExpressiveEvent event) {
        return column(event).isPresent();
    }

    static boolean directBarlineDirection(ScoreExpressiveEvent event) {
        return event.scope() == Scope.SCORE && event.start().isPresent()
                && (event.eventId().startsWith("printed-tempo-restoration:")
                        || event.eventId().startsWith("printed-sectional-tempo:")
                        || event.eventId().startsWith("printed-metric-boundary:")
                        || ScoreRhythmicFeel.read(event).isPresent());
    }

    static Optional<String> remapTargetMeasures(
            ScoreExpressiveEvent event, java.util.function.IntUnaryOperator mapper) {
        return column(event).map(c -> target(
                mapper.applyAsInt(c.measure()), c.staff(), c.count(), c.position(), c.rest()));
    }

    static ScoreExpressiveEvent offsetEvidence(ScoreExpressiveEvent event, int offset, int page) {
        var shiftedTarget = column(event).map(c -> target(
                Math.addExact(c.measure(), offset), c.staff(), c.count(), c.position(), c.rest()));
        return new ScoreExpressiveEvent(
                "page:" + page + "/" + event.eventId(),
                event.kind(),
                event.start().map(a -> a.offset(offset)),
                event.end().map(a -> a.offset(offset)),
                event.scope(),
                event.staffIndex(),
                event.staffCount(),
                shiftedTarget,
                event.strength(),
                event.qualifierText(),
                event.evidence().stream()
                        .map(
                                e ->
                                        new Evidence(
                                                e.sourceId(),
                                                page,
                                                e.visualX(),
                                                e.staffIndex(),
                                                e.staffCount(),
                                                e.printedText()))
                        .toList());
    }

    /** Called again after page concatenation, so continuation pages can use the inherited meter. */
    public static ScorePageInterpretation resolve(
            ScorePageInterpretation score, float openingBeats) {
        return resolveInternal(score, openingBeats, null);
    }

    static ScorePageInterpretation resolveWithCurrentRestClocks(
            ScorePageInterpretation score,
            float openingBeats,
            Set<Integer> currentRestOccurrences) {
        return resolveInternal(score, openingBeats, currentRestOccurrences);
    }

    private static ScorePageInterpretation resolveInternal(
            ScorePageInterpretation score,
            float openingBeats,
            Set<Integer> currentRestOccurrences) {
        if (!Float.isFinite(openingBeats)) {
            var first =
                    score.meterChanges().stream().filter(m -> m.measureIndex() == 0).findFirst();
            if (first.isEmpty()) return score;
            openingBeats = first.get().numerator() * 4f / first.get().denominator();
        }
        if (openingBeats <= 0) return score;
        var meter = new ScoreMeterMap(openingBeats, score.meterChanges());
        var events = new ArrayList<ScoreExpressiveEvent>();
        Map<NoteLane, List<Integer>> noteLanes = null;
        Map<Column, Double> restOnsets = null;
        Deque<Optional<Column>> parsedAhead = null;
        boolean firstNoteColumn = true, indexDecision = false;
        int nextEventIndex = 0;
        try (var session = ScoreNoteTiming.beginTimingSession(score)) {
            for (var event : score.expressiveEvents()) {
                nextEventIndex++;
                var c =
                        parsedAhead == null || parsedAhead.isEmpty()
                                ? column(event)
                                : parsedAhead.removeFirst();
                if (currentRestOccurrences != null
                        && currentRestOccurrences.contains(nextEventIndex - 1)) {
                    events.add(event);
                    continue;
                }
                if (c.isEmpty()) {
                    events.add(event);
                    continue;
                }
                List<Integer> indices;
                Column target = c.get();
                if (!target.rest() && !firstNoteColumn && !indexDecision) {
                    indexDecision = true;
                    if (score.expressiveEvents().size() - nextEventIndex >= 3) {
                        parsedAhead = new ArrayDeque<>();
                        if (hasThreeMoreColumns(
                                score.expressiveEvents(), nextEventIndex, parsedAhead, currentRestOccurrences))
                            noteLanes = indexNoteLanes(score);
                    }
                }
                indices = target.rest() ? List.of()
                        : noteLanes == null
                                ? targetIndicesForColumn(score, target)
                                : targetIndicesForColumn(score, target, noteLanes);
                if (!target.rest()) firstNoteColumn = false;
                double start = -1, release = -1;
                boolean proved = !indices.isEmpty();
                boolean tempoOnset = tempoDirection(event.kind());
                for (int index : indices) {
                    var note = score.notes().get(index);
                    float beats = meter.beatsInMeasure(note.measureIndex());
                    double onset = ScoreNoteTiming.beatInMeasure(note, score.notes(), beats);
                    if (tempoOnset) {
                        if (!Double.isFinite(onset) || onset < 0 || onset >= beats
                                || start >= 0 && Math.abs(onset - start) > 1e-7) proved = false;
                        start = onset;
                        continue;
                    }
                    double end =
                            onset
                                    + ScoreNoteTiming.resolvedWrittenDurationBeats(
                                            note, score.notes(), beats);
                    end = ScoreAnchor.computedOffset(note.measureIndex(), end, meter);
                    if (!Double.isFinite(onset)
                            || !Double.isFinite(end)
                            || end <= onset
                            || end > beats
                            || start >= 0
                                    && (Math.abs(onset - start) > 1e-7
                                            || Math.abs(end - release) > 1e-7)) proved = false;
                    start = onset;
                    release = end;
                }
                boolean breath = event.kind() == Kind.BREATH || event.kind() == Kind.CAESURA;
                boolean pedalCaption = false;
                if (target.rest()) {
                    pedalCaption = PrintedRestDirection.pedalCaption(event);
                    boolean onsetRole = PrintedRestDirection.allows(event.kind()) || pedalCaption;
                    boolean releaseRole = breath && event.evidence().stream()
                            .anyMatch(e -> e.sourceId().equals(SYMBOL));
                    if (onsetRole || releaseRole) {
                        float beats = meter.beatsInMeasure(target.measure());
                        if (restOnsets == null) restOnsets = new HashMap<>();
                        start = restOnsets.computeIfAbsent(target, column ->
                                PrintedRestDirection.onset(score, column.measure(), column.staff(),
                                        column.count(), column.position(), beats));
                        proved = onsetRole && Double.isFinite(start) && start >= 0 && start < beats;
                        if (releaseRole) {
                            release = restRelease(score, target, beats, start);
                            proved = Double.isFinite(release);
                        }
                    }
                }
                boolean attack =
                        event.kind() == Kind.SFORZANDO
                                || event.kind() == Kind.SFORZATO
                                || event.kind() == Kind.SFORZANDO_PIANO;
                Optional<ScoreAnchor> anchor = proved
                        ? Optional.of(new ScoreAnchor(target.measure(), breath ? release : start)
                                .canonical(meter, score.measures().size()))
                        : Optional.empty();
                events.add(
                        new ScoreExpressiveEvent(
                                event.eventId(),
                                event.kind(),
                                anchor,
                                proved
                                        ? PrintedTempoSpans.preserveEnd(
                                                event,
                                                anchor.orElseThrow(),
                                                meter,
                                                score.measures().size(),
                                                score.tempoChanges())
                                        : Optional.empty(),
                                proved
                                        ? (PrintedTempoSpans.hasOpen(event)
                                                ? Scope.UNRESOLVED
                                                : attack
                                                        ? Scope.NOTE
                                                        : breath
                                                                        || event.kind()
                                                                                == Kind.PEDAL_DOWN
                                                                        || event.kind()
                                                                                == Kind.PEDAL_UP
                                                                        || pedalCaption
                                                                        || PrintedCueSize.owns(
                                                                                event)
                                                                        || ownedPartDirection(event)
                                                                ? Scope.PART
                                                                : Scope.SCORE)
                                        : Scope.UNRESOLVED,
                                event.staffIndex(),
                                event.staffCount(),
                                event.targetEventId(),
                                event.strength(),
                                event.qualifierText(),
                                event.evidence()));
            }
        }
        return score.withExpressiveEvents(events);
    }

    private static boolean ownedPartDirection(ScoreExpressiveEvent event) {
        return event.kind() == Kind.UNRESOLVED_DIRECTION && event.evidence().stream().anyMatch(v ->
                v.staffIndex() == event.staffIndex() && v.staffCount() == event.staffCount()
                        && (v.sourceId().equals(LEDGER_CAPTION)
                            || v.sourceId().equals(SOURCE) && (SuddenDynamicText.parse(v.printedText()).isPresent()
                                || ExpressiveDirectionText.isShortBowCaption(v.printedText())
                                || "segue".equalsIgnoreCase(v.printedText()))));
    }

    public static boolean tempoDirection(Kind kind) {
        return PrintedRestDirection.allows(kind);
    }

    public static List<Integer> targetIndices(
            ScorePageInterpretation score, ScoreExpressiveEvent event) {
        var c = column(event);
        if (c.isEmpty() || c.get().rest()) return List.of();
        return targetIndicesForColumn(score, c.get());
    }

    private static List<Integer> targetIndicesForColumn(ScorePageInterpretation score, Column a) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            if (note.measureIndex() == a.measure()
                    && note.staffIndex() == a.staff()
                    && note.staffCount() == a.count()
                    && Math.abs(note.positionInMeasure() - a.position()) <= .018f) result.add(i);
        }
        return List.copyOf(result);
    }

    private record NoteLane(int measure, int staff, int count) {}

    /** Build only when at least four queries remain to share the allocation cost. */
    private static boolean hasThreeMoreColumns(
            List<ScoreExpressiveEvent> events, int from, Deque<Optional<Column>> parsedAhead,
            Set<Integer> currentRestOccurrences) {
        int found = 0;
        for (int i = from; i < events.size(); i++) {
            var value = column(events.get(i));
            parsedAhead.addLast(value);
            if (value.isPresent() && !value.get().rest()
                    && (currentRestOccurrences == null || !currentRestOccurrences.contains(i))
                    && ++found == 3) return true;
        }
        return false;
    }

    /** Original indices stay ordered; this index belongs only to one resolve call. */
    private static Map<NoteLane, List<Integer>> indexNoteLanes(ScorePageInterpretation score) {
        Map<NoteLane, List<Integer>> result = new HashMap<>();
        for (int i = 0; i < score.notes().size(); i++) {
            var note = score.notes().get(i);
            var lane = new NoteLane(note.measureIndex(), note.staffIndex(), note.staffCount());
            result.computeIfAbsent(lane, unused -> new ArrayList<>()).add(i);
        }
        return result;
    }

    private static List<Integer> targetIndicesForColumn(
            ScorePageInterpretation score, Column a, Map<NoteLane, List<Integer>> lanes) {
        var result = new ArrayList<Integer>();
        var lane = new NoteLane(a.measure(), a.staff(), a.count());
        for (int i : lanes.getOrDefault(lane, List.of())) {
            var note = score.notes().get(i);
            if (Math.abs(note.positionInMeasure() - a.position()) <= .018f) result.add(i);
        }
        return List.copyOf(result);
    }

    record CurrentRestTarget(int owner, boolean release, Scope scope) {}

    /** Existing legacy Current grammar/evidence and the unchanged .018 candidate policy. */
    static Optional<CurrentRestTarget> currentRestTarget(
            ScorePageInterpretation fullCurrent, ScoreExpressiveEvent event) {
        var c = column(event);
        if (c.isEmpty() || !c.get().rest()) return Optional.empty();
        boolean release =
                (event.kind() == Kind.BREATH || event.kind() == Kind.CAESURA)
                        && event.evidence().stream().anyMatch(e -> e.sourceId().equals(SYMBOL));
        if (!PrintedRestDirection.allows(event.kind()) && !release)
            throw new IllegalArgumentException("Unsupported dependent Current rest direction role");
        var owners =
                PrintedRestDirection.currentTargetIndices(
                        fullCurrent,
                        c.get().measure(),
                        c.get().staff(),
                        c.get().count(),
                        c.get().position());
        if (owners.size() != 1)
            throw new IllegalArgumentException(
                    "Ambiguous or unresolved Current rest direction target");
        int owner = owners.get(0);
        if (fullCurrent.rests().get(owner).isFullMeasure())
            throw new IllegalArgumentException(
                    "Full-measure Current rest role needs a separate clock contract");
        return Optional.of(
                new CurrentRestTarget(owner, release, release ? Scope.PART : Scope.SCORE));
    }
}
