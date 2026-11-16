// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Stateless, platform-independent decoder for six-class masks and their source grayscale pixels.
 */
public final class SheetInterpreter {
    private SheetInterpreter() {}

    public record NumberToken(
            int value, float left, float top, float right, float bottom, float annotationLeft) {
        public NumberToken {
            checkBox(left, top, right, bottom);
            if (!Float.isFinite(annotationLeft) || annotationLeft < 0 || annotationLeft > 1)
                throw new IllegalArgumentException(
                        "annotationLeft must be a normalized coordinate");
        }

        MeasureNumberReconciler.NumberToken internal() {
            return new MeasureNumberReconciler.NumberToken(
                    value, left, top, right, bottom, annotationLeft);
        }
    }

    public enum WordAdmission {
        UNCLASSIFIED,
        CONTEXT_QUALIFIED
    }

    public record Word(
            String text,
            float left,
            float top,
            float right,
            float bottom,
            WordAdmission admission) {
        public Word(String text, float left, float top, float right, float bottom) {
            this(text, left, top, right, bottom, WordAdmission.UNCLASSIFIED);
        }

        public Word {
            Objects.requireNonNull(text);
            Objects.requireNonNull(admission);
            checkBox(left, top, right, bottom);
        }

        PlayingTechniqueDetector.Word internal() {
            return new PlayingTechniqueDetector.Word(
                    text,
                    left,
                    top,
                    right,
                    bottom,
                    admission == WordAdmission.CONTEXT_QUALIFIED
                            ? PlayingTechniqueDetector.WordAdmission.CONTEXT_QUALIFIED
                            : PlayingTechniqueDetector.WordAdmission.UNCLASSIFIED);
        }
    }

    /**
     * Optional output from the caller's OCR engine, with normalized 0..1 page boxes. Meter changes
     * are explicit, validated readings at zero-based logical measure indices.
     */
    public record Annotations(
            List<NumberToken> measureNumbers,
            List<NumberToken> tempoNumbers,
            List<NumberToken> restCounts,
            List<Word> words,
            List<ScoreMeterChange> meters,
            List<Word> tabWords) {
        public static final Annotations EMPTY =
                new Annotations(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        public Annotations(
                List<NumberToken> measureNumbers,
                List<NumberToken> tempoNumbers,
                List<NumberToken> restCounts,
                List<Word> words,
                List<ScoreMeterChange> meters) {
            this(measureNumbers, tempoNumbers, restCounts, words, meters, List.of());
        }

        public Annotations {
            measureNumbers = List.copyOf(measureNumbers);
            tempoNumbers = List.copyOf(tempoNumbers);
            restCounts = List.copyOf(restCounts);
            words = List.copyOf(words);
            meters = List.copyOf(meters);
            tabWords = List.copyOf(tabWords);
        }
    }

    private static void checkBox(float left, float top, float right, float bottom) {
        if (!Float.isFinite(left)
                || !Float.isFinite(top)
                || !Float.isFinite(right)
                || !Float.isFinite(bottom)
                || left < 0
                || top < 0
                || right > 1
                || bottom > 1
                || left >= right
                || top >= bottom)
            throw new IllegalArgumentException(
                    "OCR boxes must be ordered normalized page coordinates");
    }

    public static ScorePageInterpretation analyze(
            byte[] labels, byte[] gray, int width, int height) {
        return analyze(labels, gray, width, height, Annotations.EMPTY);
    }

    public static ScorePageInterpretation analyze(
            byte[] labels, byte[] gray, int width, int height, Annotations annotations) {
        try {
            return analyze(labels, gray, width, height, annotations, null);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Unexpected optional OCR failure", failure);
        }
    }

    /**
     * Optional caller-owned OCR inference; the default overload has no ONNX or model dependency.
     */
    public static ScorePageInterpretation analyze(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            Annotations annotations,
            MusicalOcr ocr)
            throws Exception {
        return analyze(labels, gray, width, height, annotations, ocr, -1);
    }

    /** Explicit physical page for retained printed arcs; legacy calls do not assume page zero. */
    public static ScorePageInterpretation analyze(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            Annotations annotations,
            MusicalOcr ocr,
            int physicalPage)
            throws Exception {
        return analyze(labels, gray, width, height, annotations, ocr, physicalPage, null);
    }

    /** Original pixels retain source authority; optional decoder pixels only feed geometry. */
    public static ScorePageInterpretation analyze(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            Annotations annotations,
            MusicalOcr ocr,
            int physicalPage,
            byte[] decoderGray)
            throws Exception {
        return analyze(labels, gray, width, height, annotations, ocr, physicalPage, decoderGray, null);
    }

    static ScorePageInterpretation analyze(
            byte[] labels, byte[] gray, int width, int height, Annotations annotations,
            MusicalOcr ocr, int physicalPage, byte[] decoderGray,
            TablatureDecoder.Prepared preparedTabs)
            throws Exception {
        return analyzeWithPrintedSources(labels, gray, width, height, annotations, ocr,
                physicalPage, decoderGray, preparedTabs).score();
    }

    record AnalyzedPage(
            ScorePageInterpretation score,
            List<ScoreNoteEvent> originalNotes,
            SourceLocalAccidental accidentalSource,
            List<MeasureRegion> accidentalMeasures,
            List<ScoreNoteEvent> closedTripletSharpOwners,
            List<List<ScoreNoteEvent>> harmonicTails) {}

    static AnalyzedPage analyzeWithPrintedSources(
            byte[] labels, byte[] gray, int width, int height, Annotations annotations,
            MusicalOcr ocr, int physicalPage, byte[] decoderGray,
            TablatureDecoder.Prepared preparedTabs)
            throws Exception {
        return analyzeWithPrintedSources(
                labels,
                gray,
                width,
                height,
                annotations,
                ocr,
                physicalPage,
                decoderGray,
                preparedTabs,
                null);
    }

    static AnalyzedPage analyzeWithPrintedSources(
            byte[] labels,
            byte[] gray,
            int width,
            int height,
            Annotations annotations,
            MusicalOcr ocr,
            int physicalPage,
            byte[] decoderGray,
            TablatureDecoder.Prepared preparedTabs,
            ScoreMeterChange inheritedMeter)
            throws Exception {
        if (decoderGray != null && (gray == null || decoderGray.length != gray.length))
            throw new IllegalArgumentException("Decoder grayscale dimensions must match source");
        if (physicalPage < -1) throw new IllegalArgumentException("Invalid physical source page");
        long pixels = (long) width * height;
        if (width <= 0
                || height <= 0
                || pixels > 20_000_000
                || labels == null
                || gray == null
                || labels.length != pixels
                || gray.length != pixels)
            throw new IllegalArgumentException(
                    "Expected equally sized masks and grayscale, at most 20 million pixels");
        for (byte label : labels)
            if (label < 0 || label > 5)
                throw new IllegalArgumentException("Labels must be in 0..5");
        Objects.requireNonNull(annotations);
        byte[] tabSourceGray = gray;
        var sourceNumbers = annotations.measureNumbers.stream().map(NumberToken::internal).toList();
        var sourceAnnotations =
                ScorePageAnnotationSource.fromRawReader(
                        gray,
                        width,
                        height,
                        annotations.words.stream()
                                .map(
                                        w ->
                                                new ScorePageAnnotationSource.ReaderWord(
                                                        w.text(),
                                                        w.left(),
                                                        w.top(),
                                                        w.right(),
                                                        w.bottom()))
                                .toList(),
                        sourceNumbers);
        var prepared = preparedTabs == null
                ? TablatureDecoder.prepare(gray, width, height, annotations.words, annotations.tabWords)
                : preparedTabs;
        var tabWords = prepared.words();
        var tabs = prepared.withRhythm();
        labels = TablatureDecoder.withoutTabs(labels, width, height, tabs, false);
        gray = TablatureDecoder.withoutTabs(gray, width, height, tabs, true);
        byte[] annotationGray = gray;
        if (decoderGray != null)
            gray = TablatureDecoder.withoutTabs(decoderGray, width, height, tabs, true);
        var selectedOrigins = new SelectedMeasureOrigins();
        var geometry = NativeDecoderStages.geometry(labels, gray, width, height, selectedOrigins);
        var measures = geometry.measures();
        labels = geometry.labels();
        measures = TablatureDecoder.reconcileMeasures(measures, tabs, width, height);
        var numbers = new java.util.ArrayList<>(sourceNumbers);
        if (MeasureNumberReconciler.firstMeasureNumber(measures, numbers) == 0 && ocr != null)
            for (var token :
                    ocr.supplementSystemMeasureNumbers(
                            labels,
                            annotationGray,
                            width,
                            height,
                            measures,
                            annotations.measureNumbers)) numbers.add(token.internal());
        var recognizedRests = new RecognizedMultiRestOrigins(selectedOrigins);
        var rests =
                MultiMeasureRestDetector.detect(
                        labels,
                        gray,
                        width,
                        height,
                        measures,
                        annotations.restCounts.stream().map(NumberToken::internal).toList(),
                        recognizedRests);
        var rawMeasures = measures;
        // Unnumbered systems still need the same bounded geometry repair as the native reader.
        measures =
                MeasureNumberReconciler.reconcile(measures, numbers, rests, labels, gray, width, height);
        recognizedRests.expanded(rawMeasures, measures);
        var rejectedAnchorTops = new HashSet<Float>();
        var words = annotations.words.stream().map(Word::internal).toList();
        measures =
                PrintedMeasureRhythmGuard.reconcile(
                        rawMeasures, measures, labels, gray, width, height, rejectedAnchorTops, words);
        var recoveredCadence =
                OverlappingQuarterCadence.recover(
                        rawMeasures, measures, labels, gray, width, height);
        measures = recoveredCadence.measures();
        labels = recoveredCadence.labels();
        measures =
                VerifiedOpeningMeterSlot.recover(
                        measures, labels, gray, annotationGray, width, height);
        measures = ClosedSixteenthPairBeforeHalf.recover(
                measures, labels, gray, annotationGray, width, height);
        measures =
                HalfDyadBeforeSixteenthBar.preserveInlineTriple(
                        rawMeasures, measures, labels, gray, annotationGray, width, height);
        measures =
                BeamedFlatBarline.joinLedgerOctaveFragments(
                        measures, labels, gray, annotationGray, width, height);
        measures =
                BeamedFlatBarline.joinPairedFlatTails(
                        measures, labels, gray, annotationGray, width, height);
        var beforeHarmonicJoin = measures;
        var harmonicJoinedMeasures =
                HarmonicBeamBarline.join(measures, labels, gray, annotationGray, width, height);
        measures = harmonicJoinedMeasures;
        var tripleRuns =
                MidstaffSixteenthTripleBars.recover(
                        measures, labels, gray, annotationGray, width, height);
        measures = tripleRuns.measures();
        measures =
                HalfDyadBeforeSixteenthBar.recoverDotted(
                        measures, labels, gray, annotationGray, width, height);
        var fittedWithOverlaps = measures;
        measures = PrintedMeasureRhythmGuard.rejectUpperLedgerRows(measures, gray, width, height);
        final var retainedRegions = measures;
        var rejectedLedgerRows = retainedRegions == fittedWithOverlaps ? List.<MeasureRegion>of()
                : fittedWithOverlaps.stream().filter(region -> !retainedRegions.contains(region)).toList();
        measures = InterruptedStaffHeader.discard(
                measures, labels, gray, annotationGray, width, height);
        recognizedRests.reindexed(measures);
        var numberingAnchors =
                rejectedAnchorTops.isEmpty()
                        ? numbers
                        : numbers.stream()
                                .filter(n -> !rejectedAnchorTops.contains(n.top()))
                                .toList();
        int firstMeasureNumber =
                MeasureNumberReconciler.firstMeasureNumber(measures, numberingAnchors);
        if (firstMeasureNumber == 0 && ocr != null)
            firstMeasureNumber =
                    ocr.firstSystemMeasureNumber(labels, annotationGray, width, height, measures);
        var clockPartSource =
                ScorePartProducerSource.capture(
                        sourceAnnotations, tabSourceGray, width, height, measures);
        var score = OmrScoreInterpreter.analyze(
                labels, gray, width, height, measures, annotationGray, rejectedLedgerRows);
        var clockAnalysis = score;
        score =
                RecognizedMultiRestProjection.apply(
                        score, measures, labels, gray, width, height, recognizedRests);
        var wholeMeterAlias = MeterNumeralWholeNoteAlias.capture(
                score.notes(), measures, labels, gray, annotationGray, width, height);
        var noteOwners = InlineTripleMeterNoteAlias.remove(
                wholeMeterAlias.notes(), measures, labels, gray, annotationGray, width, height);
        noteOwners = tripleRuns.retain(noteOwners, labels, gray, annotationGray, width, height);
        var rhythm =
                TripletRhythmDetector.withRests(
                        noteOwners, score.rests(), measures, gray, width, height);
        var notes = rhythm.notes();
        var staffs =
                ScoreDynamicsDetector.alignStaffs(
                        OmrScoreInterpreter.techniqueStaffs(labels, gray, width, height, measures),
                        notes,
                        height);
        // Thin octave dashes can fade in the paper-corrected note raster.
        notes = OctaveMarkDetector.apply(words, staffs, measures, notes, annotationGray, width, height);
        notes =
                FingeringAnnotationFilter.apply(
                        words, staffs, measures, notes, gray, width, height);
        if (!notes.isEmpty() && !staffs.isEmpty()) {
            var ornamentWords = new java.util.ArrayList<PlayingTechniqueDetector.Word>();
            for (var word : words) {
                String token = MusicalOcrEvidence.ornamentToken(word.text());
                if (!token.isEmpty())
                    ornamentWords.add(
                            new PlayingTechniqueDetector.Word(
                                    token, word.left(), word.top(), word.right(), word.bottom()));
            }
            if (ocr != null)
                ornamentWords.addAll(ocr.ornamentWords(annotationGray, width, height, staffs));
            if (ocr != null)
                ornamentWords.addAll(
                        ocr.crossRowPortWords(
                                annotationGray, width, height, staffs, measures, notes));
            notes =
                    PortableNoteOrnaments.apply(
                            GlyphResources.ornaments(),
                            labels,
                            gray,
                            width,
                            height,
                            measures,
                            notes,
                            ornamentWords);
        }
        notes = ArtificialHarmonics.apply(labels, gray, width, height, measures, notes, staffs);
        notes = ScoreTiePitchGuard.apply(notes, score.keyChanges());
        notes = DottedCadenceShortBeams.apply(notes, measures, staffs, gray, width, height);
        List<PlayingTechniqueDetector.Word> dynamicEvidence = words;
        DirectionWordProvenance.Acquisition dynamicAcquisition = null;
        MusicalOcr.SuddenDynamicAcquisition suddenAcquisition = null;
        if (ocr != null) {
            dynamicEvidence = new java.util.ArrayList<>(words);
            suddenAcquisition =
                    ocr.acquiredDynamicsWithSuddenSource(
                            annotationGray, width, height, staffs, words);
            dynamicAcquisition = suddenAcquisition.expressionAcquisition();
            for (var word : dynamicAcquisition.words())
                if (!dynamicEvidence.contains(word)) dynamicEvidence.add(word);
        }
        var dynamicWords =
                staffs.isEmpty()
                        ? words
                        : GlyphResources.dynamics()
                                .recognize(gray, width, height, staffs, dynamicEvidence);
        if (ocr != null)
            dynamicWords =
                    ocr.supplementDynamics(
                            annotationGray, width, height, staffs, measures, notes, dynamicWords);
        if (suddenAcquisition != null)
            dynamicWords = suddenAcquisition.normalize(dynamicWords, gray, width, height);
        List<PlayingTechniqueDetector.Word> techniqueWords = words;
        if (ocr != null) {
            techniqueWords = new java.util.ArrayList<>(words);
            techniqueWords.addAll(
                    ocr.contextualDirections(
                            annotationGray,
                            width,
                            height,
                            staffs,
                            measures,
                            notes,
                            dynamicWords,
                            words));
            techniqueWords.addAll(
                    ocr.techniques(annotationGray, width, height, staffs, measures, notes));
        }
        var articulationWords = new java.util.ArrayList<>(techniqueWords);
        for (var word : dynamicWords)
            if (!ExpressiveDirectionText.parse(word.text()).isEmpty()
                    && !articulationWords.contains(word)) articulationWords.add(word);
        notes =
                PrintedWordArticulations.apply(
                        articulationWords, staffs, measures, notes, labels, gray, width, height);
        var meters = new java.util.ArrayList<>(annotations.meters);
        for (var meter : wholeMeterAlias.inlineMeters()) {
            if (meters.stream().noneMatch(current -> current.measureIndex() == meter.measureIndex()))
                meters.add(meter);
        }
        var meterEvidence =
                ocr == null
                        ? new MusicalOcr.MeterEvidence(List.of(), List.of())
                        : ocr.meterEvidence(labels, annotationGray, width, height, measures, notes);
        var inferredMeters = ocr != null
                ? meterEvidence.changes()
                : MeterFontMatcher.standaloneReadings(labels, gray, width, height, measures, notes);
        if (tripleRuns.first() >= 0
                && meters.stream().noneMatch(m -> m.measureIndex() == tripleRuns.first()))
            meters.add(new ScoreMeterChange(tripleRuns.first(), 3, 4));
        if (!inferredMeters.isEmpty()) {
            var occupiedMeasures = new HashSet<Integer>();
            for (var meter : meters) occupiedMeasures.add(meter.measureIndex());
            for (var meter : inferredMeters)
                if (occupiedMeasures.add(meter.measureIndex())) meters.add(meter);
        }
        meters.sort(java.util.Comparator.comparingInt(ScoreMeterChange::measureIndex));
        var dynamicDetection =
                ScoreDynamicsDetector.detectWithEvidence(
                        dynamicWords, staffs, measures, notes, gray, width, height);
        List<PlayingTechniqueDetector.Word> tempoWords = words;
        if (ocr != null) {
            tempoWords = new java.util.ArrayList<>(words);
            tempoWords.addAll(ocr.tempoWords(annotationGray, width, height));
        }
        var decoded =
                TablatureDecoder.apply(
                        new ScorePageInterpretation(
                                        measures,
                                        notes,
                                        firstMeasureNumber,
                                        score.keyChanges(),
                                        TempoChangeDetector.detectWithWords(
                                                TempoChangeDetector.withIsolatedDigits(
                                                        annotations.tempoNumbers.stream()
                                                                .map(NumberToken::internal)
                                                                .toList(),
                                                        tempoWords,
                                                        gray,
                                                        width,
                                                        height),
                                                tempoWords,
                                                gray,
                                                width,
                                                height,
                                                measures),
                                        meters,
                                        rhythm.rests(),
                                        PlayingTechniqueDetector.detectWithBows(
                                                techniqueWords,
                                                staffs,
                                                measures,
                                                notes,
                                                labels,
                                                gray,
                                                width,
                                                height),
                                        dynamicDetection.changes())
                                .withMultiRestGroups(
                                        PrintedMultiRestGroups.capture(
                                                score,
                                                measures,
                                                labels,
                                                gray,
                                                width,
                                                height,
                                                recognizedRests))
                                .withTremoloSource(
                                        score.tremoloSource()
                                                .retainFinalHeads(score.notes(), notes)
                                                .onPage(Math.max(0, physicalPage))),
                        tabs,
                        width,
                        height);
        for (var meter : annotations.meters)
            if (meter.measureIndex() >= decoded.measures().size())
                throw new IllegalArgumentException(
                        "Meter change is outside the detected measure range");
        var finalScore =
                PrintedPageEvidence.rejectStafflessPage(
                        TabMeter.apply(decoded, tabs, tabWords, width, height),
                        gray,
                        width,
                        height,
                        !tabs.isEmpty());
        finalScore =
                WholeRestMeterContext.apply(
                        finalScore,
                        gray,
                        width,
                        height,
                        staffs,
                        meterEvidence.changes(),
                        meterEvidence.headers());
        // Resolve compound ratios only after the final meter and measure remapping are known.
        finalScore =
                finalScore.withNotes(
                        TripletRhythmDetector.compoundDuplets(
                                finalScore.notes(),
                                finalScore.measures(),
                                finalScore.meterChanges(),
                                gray,
                                width,
                                height),
                        java.util.stream.IntStream.range(0, finalScore.notes().size())
                                .boxed()
                                .toList());
        finalScore =
                finalScore.withNotes(
                        TripletRhythmDetector.neutralPrintedPairs(
                                finalScore.notes(),
                                finalScore.measures(),
                                finalScore.meterChanges(),
                                annotationGray,
                                width,
                                height),
                        java.util.stream.IntStream.range(0, finalScore.notes().size())
                                .boxed()
                                .toList());
        finalScore = finalScore.withNotes(
                TripletRhythmDetector.ordinarySixteenthDyadBars(finalScore.notes(), finalScore.rests(),
                        finalScore.measures(), finalScore.meterChanges(), gray, width, height),
                java.util.stream.IntStream.range(0, finalScore.notes().size()).boxed().toList());
        var closedTriplets =
                WrappedEighthTriplets.closeQuarterBeatBars(
                        finalScore.notes(),
                        finalScore.rests(),
                        finalScore.measures(),
                        finalScore.meterChanges(),
                        gray,
                        width,
                        height,
                        labels,
                        annotationGray);
        closedTriplets =
                ClosedOctaveTripletBars.apply(
                        closedTriplets,
                        finalScore.rests(),
                        finalScore.measures(),
                        finalScore.meterChanges(),
                        gray,
                        width,
                        height);
        if (closedTriplets != finalScore.notes())
            finalScore = finalScore.withNotes(closedTriplets,
                    java.util.stream.IntStream.range(0, closedTriplets.size()).boxed().toList());
        var mixedBeamTransition =
                EighthTripletsBeforeSixteenths.capture(finalScore, annotationGray, width, height);
        finalScore = mixedBeamTransition.score();
        finalScore =
                OpeningBeamPickup.apply(
                        finalScore,
                        labels,
                        gray,
                        width,
                        height,
                        staffs,
                        meterEvidence.changes(),
                        meterEvidence.headers(),
                        clockAnalysis);
        var expressionWords =
                ocr == null
                        ? techniqueWords
                        : ocr.expressionWords(
                                techniqueWords, dynamicAcquisition, annotationGray, width, height);
        var withExpressions =
                ScoreExpressionDetector.apply(
                        ScoreFermataDetector.withFermatas(
                                finalScore
                                        .withExpressiveEvents(
                                                finalScore.measures().isEmpty()
                                                        ? List.of()
                                                        : dynamicDetection.events())
                                        .withPlaybackDirections(
                                                ScoreNavigationDetector.detect(
                                                        ScoreNavigationDetector.withPdfDestinations(words, tabWords),
                                                        NavigationSignGlyphs.detect(
                                                                gray, width, height, staffs),
                                                        staffs,
                                                        finalScore.measures(),
                                                        width,
                                                        height)),
                                labels,
                                gray,
                                width,
                                height,
                                staffs),
                        expressionWords,
                        staffs,
                        gray,
                        width,
                        height,
                        GlyphResources.expressions());
        var finalInterpretation =
                TabBoundaryTies.apply(
                        ScorePedalDetector.apply(withExpressions, gray, width, height, staffs),
                        tabs,
                        tabSourceGray,
                        width,
                        height);
        finalInterpretation =
                PrintedRepeatNavigation.apply(
                        finalInterpretation, words, staffs, gray, width, height, physicalPage);
        finalInterpretation =
                finalInterpretation.withOpeningPickup(
                        firstMeasureNumber <= 1
                                && MeasureNumberReconciler.provesOpeningPickup(
                                        finalInterpretation.measures(), numbers)
                                && OpeningPickupContent.keepsNumberedPickup(
                                        finalInterpretation.notes(),
                                        finalInterpretation.rests(),
                                        finalInterpretation.meterChanges()));
        finalInterpretation =
                VerifiedCourtesyKeyTail.apply(
                        VerifiedCourtesyBoundary.apply(
                                finalInterpretation, labels, gray, width, height),
                        labels,
                        gray,
                        width,
                        height);
        final var pedalScore = finalInterpretation;
        final var pedalGray = gray;
        finalInterpretation =
                ScorePedalDetector.withGlyphs(
                        finalInterpretation,
                        GlyphResources.pedals()
                                .detect(finalInterpretation.measures(), staffs, gray, width, height)
                                .stream()
                                .map(
                                        mark ->
                                                PrintedPedalColumns.locate(
                                                        mark, pedalScore, pedalGray, width, height))
                                .toList(),
                        width,
                        Math.max(0, physicalPage));
        finalInterpretation =
                ScoreArpeggioDetector.apply(
                        finalInterpretation,
                        gray,
                        width,
                        height,
                        staffs,
                        Math.max(0, physicalPage), annotationGray);
        finalInterpretation =
                PrintedCueSize.apply(
                        finalInterpretation, expressionWords, staffs, gray, width, height);
        PrintedPhraseEndpoints.Pending phrasePending = null;
        if (physicalPage >= 0)
            try {
                var captured =
                        PrintedSlurRecognition.captureObserved(
                                finalInterpretation,
                                physicalPage,
                                gray,
                                labels,
                                width,
                                height,
                                staffs);
                if (captured.isPresent()) {
                    finalInterpretation = captured.get().score();
                    phrasePending = captured.get().pending();
                }
            } catch (RuntimeException unavailable) {
                Diagnostics.log("Printed phrase capture unavailable: " + unavailable.getMessage());
            }
        if (physicalPage >= 0)
            finalInterpretation =
                    PrintedNaturalHarmonicDetector.apply(
                            finalInterpretation,
                            labels,
                            gray,
                            width,
                            height,
                            staffs,
                            techniqueWords,
                            physicalPage,
                            Float.NaN);
        if (physicalPage >= 0)
            finalInterpretation =
                    PrintedFallOffDetector.apply(
                            finalInterpretation,
                            gray,
                            width,
                            height,
                            staffs,
                            techniqueWords,
                            physicalPage);
        if (physicalPage >= 0)
            finalInterpretation =
                    PrintedParenthesisDetector.apply(
                            finalInterpretation,
                            gray,
                            width,
                            height,
                            staffs,
                            techniqueWords,
                            physicalPage);
        finalInterpretation = InSystemClosingDyad.apply(
                finalInterpretation, annotationGray, width, height);
        finalInterpretation = OwnedTertiaryBeamlets.applyMonophonic(
                finalInterpretation, gray, width, height);
        finalInterpretation =
                ClosedDottedSixteenths.applyClosedMonophonicHooks(
                        finalInterpretation, inheritedMeter, gray, width, height);
        finalInterpretation =
                ClosedDottedSixteenths.applyClosedTailDyadHooks(
                        finalInterpretation, inheritedMeter, gray, width, height);
        var harmonicTriplets =
                ClosedHarmonicTriplets.capture(
                        finalInterpretation.notes(), finalInterpretation.rests(),
                        finalInterpretation.measures(), finalInterpretation.meterChanges(),
                        inheritedMeter, annotationGray, width, height);
        if (harmonicTriplets.notes() != finalInterpretation.notes())
            finalInterpretation = finalInterpretation.withNotes(
                    harmonicTriplets.notes(),
                    java.util.stream.IntStream.range(0, harmonicTriplets.notes().size()).boxed().toList());
        if (physicalPage >= 0)
            finalInterpretation =
                    PrintedBeamSource.apply(
                            finalInterpretation,
                            labels,
                            gray,
                            width,
                            height,
                            staffs,
                            physicalPage,
                            phrasePending);
        finalInterpretation = WrappedEighthTriplets.projectOpenedQuarterTripletBeams(
                finalInterpretation, labels, gray, annotationGray, width, height, words);
        finalInterpretation = harmonicTriplets.projectBeams(finalInterpretation, physicalPage);
        finalInterpretation = mixedBeamTransition.projectBeams(finalInterpretation, physicalPage);
        var accidentalTriplets =
                WrappedEighthTriplets.closeAccidentalMarkedTripletBars(
                        finalInterpretation, labels, gray, width, height);
        finalInterpretation = accidentalTriplets.score();
        finalInterpretation =
                PrintedPassOmission.apply(
                        finalInterpretation, words, staffs, gray, width, height, physicalPage);
        finalInterpretation =
                PrintedDottedTripletBar.apply(
                        finalInterpretation, null, gray, annotationGray, width, height);
        finalInterpretation =
                TerminalDottedCadenceHooks.apply(finalInterpretation, null, gray, width, height);
        finalInterpretation = ClosedSixteenthDyads.apply(finalInterpretation, gray, width, height);
        finalInterpretation = ClosedDottedSixteenths.apply(finalInterpretation, gray, width, height);
        finalInterpretation =
                ClosedDottedThirds.apply(
                        finalInterpretation, inheritedMeter, labels, gray, width, height);
        finalInterpretation =
                ClosedDottedSixteenths.applyClosedOctaveFragments(
                        finalInterpretation, inheritedMeter, gray, width, height, physicalPage);
        finalInterpretation =
                ClosedSixteenthPairBeforeHalf.captureBareHalf(
                        finalInterpretation, gray, annotationGray, width, height);
        finalInterpretation =
                ClosedDottedSixteenths.applyReversedOctaveHook(
                        finalInterpretation, inheritedMeter, labels, gray, width, height);
        finalInterpretation =
                ClosedDoubleHarmonicQuarters.apply(finalInterpretation, gray, width, height);
        finalInterpretation =
                DottedDyadMeasureSeparator.restorePrintedHooks(
                        finalInterpretation, gray, width, height, physicalPage);
        finalInterpretation =
                DottedOpeningBeamHooks.apply(
                        finalInterpretation, gray, annotationGray, width, height);
        finalInterpretation =
                ClosedDottedSixteenths.restoreUnclaimedOctaveBeamGroups(
                        finalInterpretation, inheritedMeter, gray, annotationGray, width, height,
                        physicalPage);
        finalInterpretation = PenultimateHarmonicQuarterAlias.capture(
                finalInterpretation, labels, annotationGray, gray, width, height);
        var harmonicTail =
                HarmonicBeamBarline.recoverTail(
                        finalInterpretation,
                        beforeHarmonicJoin,
                        harmonicJoinedMeasures,
                        gray,
                        annotationGray,
                        width,
                        height);
        finalInterpretation = HalfDyadBeforeSixteenthBar.restoreCompactTripleQuarter(
                harmonicTail.score(), gray, annotationGray, width, height);
        finalInterpretation =
                finalInterpretation.withClockContextSource(
                        ScoreClockContextCapture.capture(
                                labels,
                                gray,
                                width,
                                height,
                                finalInterpretation,
                                clockAnalysis,
                                clockPartSource,
                                meterEvidence.provenance(),
                                tabSourceGray));
        var pitched = ArtificialHarmonics.applyThirdPitches(
                labels, gray, width, height, finalInterpretation);
        var touches = OpenStringHarmonics.capture(
                labels, annotationGray, gray, width, height, pitched, words);
        var cadences = PrintedCadenceDurations.captureWithRejectedLedgerRows(
                touches, labels, gray, width, height, rejectedLedgerRows, annotationGray);
        cadences = ClosedLedgerHalfDyad.capture(cadences, annotationGray, width, height);
        return new AnalyzedPage(
                TerminalMixedHarmonicCadence.capture(
                        cadences, labels, annotationGray, gray, width, height),
                clockAnalysis.notes(),
                clockAnalysis.clockProducerSource().localAccidentals(),
                measures,
                accidentalTriplets.sharpOwners(),
                harmonicTail.tails());
    }
}
