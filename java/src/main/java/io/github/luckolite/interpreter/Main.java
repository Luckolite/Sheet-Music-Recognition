// Copyright 2026 Luckolite
// SPDX-License-Identifier: Apache-2.0
package io.github.luckolite.interpreter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** File bridge used by the Python image/PDF frontend. No Android classes or external JARs. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 11 || args.length % 2 != 1)
            throw new IllegalArgumentException(
                    "Expected page, output, meter, key and optional page-index, decoder-gray and"
                        + " instrument pairs");
        int physicalPage = -1;
        Path decoderRaster = null;
        boolean standardViolin = false;
        for (int i = 5; i < args.length; i += 2) {
            if (args[i].equals("--page-index") && physicalPage == -1) {
                physicalPage = Integer.parseInt(args[i + 1]);
                if (physicalPage < 0 || physicalPage > 100000)
                    throw new IllegalArgumentException("Physical source page must be 0..100000");
            } else if (args[i].equals("--decoder-gray") && decoderRaster == null) {
                decoderRaster = Path.of(args[i + 1]);
            } else if (args[i].equals("--instrument")
                    && !standardViolin
                    && args[i + 1].equals("violin")) {
                standardViolin = true;
            } else
                throw new IllegalArgumentException("Unknown, invalid or duplicate decoder option");
        }
        int numerator = Integer.parseInt(args[2]), denominator = Integer.parseInt(args[3]);
        var initialMeter = new ScoreMeterChange(0, numerator, denominator);
        int initialKey = Integer.parseInt(args[4]);
        if (initialKey < -7 || initialKey > 7)
            throw new IllegalArgumentException("Key must be -7..7");
        byte[] labels, gray;
        int width, height;
        var annotations = SheetInterpreter.Annotations.EMPTY;
        try (var in =
                new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(Files.newInputStream(Path.of(args[0])), 32 * 1024)))) {
            if (in.readInt() != 0x52535031) throw new IOException("Not an RSP1 page");
            width = in.readInt();
            height = in.readInt();
            long size = (long) width * height;
            if (width < 1 || height < 1 || size > 20_000_000)
                throw new IOException("Invalid page dimensions");
            labels = new byte[(int) size];
            gray = new byte[(int) size];
            try {
                in.readFully(labels);
                in.readFully(gray);
            } catch (EOFException error) {
                if (error.getMessage() != null) throw error;
                throw new EOFException("Truncated page pixels");
            }
            int first = in.read();
            if (first != -1) {
                int magic =
                        (first << 24)
                                | (in.readUnsignedByte() << 16)
                                | (in.readUnsignedByte() << 8)
                                | in.readUnsignedByte();
                if (magic != 0x4f435231) throw new IOException("Unsupported annotation data");
                var numbers = readNumbers(in);
                var tempos = readNumbers(in);
                var rests = readNumbers(in);
                var words = readWords(in, readCount(in));
                int count = readCount(in);
                var meters = new ArrayList<ScoreMeterChange>(count);
                for (int i = 0; i < count; i++)
                    meters.add(new ScoreMeterChange(in.readInt(), in.readInt(), in.readInt()));
                List<SheetInterpreter.Word> nativeTabWords = List.of();
                int next = in.read();
                if (next != -1) {
                    count =
                            (next << 24)
                                    | (in.readUnsignedByte() << 16)
                                    | (in.readUnsignedByte() << 8)
                                    | in.readUnsignedByte();
                    if (count < 0 || count > 10000)
                        throw new IOException("Annotation limit exceeded");
                    nativeTabWords = readWords(in, count);
                }
                if (in.read() != -1) throw new IOException("Unexpected trailing bytes");
                annotations =
                        new SheetInterpreter.Annotations(
                                numbers, tempos, rests, words, meters, nativeTabWords);
            }
        }
        var preparedTabs = TablatureDecoder.prepare(
                gray, width, height, annotations.words(), annotations.tabWords());
        var tabWarnings = new ArrayList<String>();
        if (preparedTabs.recognized().stream().anyMatch(t -> t.frets().isEmpty()))
            tabWarnings.add(
                    "Tablature detected but no reliable fret OCR supplied; paired notation is"
                        + " retained where available.");
        var tabs = preparedTabs.withRhythm();
        if (tabs.stream()
                .filter(t -> t.standardTop() < 0)
                .flatMap(t -> t.frets().stream())
                .anyMatch(f -> f.duration() == 0 && f.beams() == 0))
            tabWarnings.add(
                    "Some standalone tab durations are unknown and playback timing is estimated.");
        if (!tabs.isEmpty())
            tabWarnings.add(
                    "Guitar effects require explicit OCR symbols; unsupported graphical bend curves"
                        + " and performance directions are not inferred.");
        if (!tabs.isEmpty())
            tabWarnings.add(
                    "Tab pitch uses an explicit tuning header when available, otherwise standard"
                        + " six- or seven-string guitar tuning.");
        byte[] decoderGray = null;
        if (decoderRaster != null) {
            if (Files.size(decoderRaster) != (long) width * height)
                throw new IOException("Decoder raster length differs from source");
            decoderGray = Files.readAllBytes(decoderRaster);
        }
        var decoded =
                SheetInterpreter.analyzeWithPrintedSources(
                        labels,
                        gray,
                        width,
                        height,
                        annotations,
                        null,
                        physicalPage,
                        decoderGray,
                        preparedTabs,
                        initialMeter,
                        initialKey,
                        standardViolin);
        var score = ScoreTiePitchGuard.withInitialKeyContext(decoded.score(), initialKey);
        score = ScoreTupletMeterContext.apply(score, initialMeter);
        score =
                ClosedSixteenthDyads.applyClosedMonophonicThreeFour(
                        score,
                        decoderGray == null ? gray : decoderGray,
                        gray,
                        width,
                        height,
                        physicalPage,
                        annotations.words());
        score = PrintedLedgerSlurContinuations.apply(
                score, decoderGray == null ? gray : decoderGray, gray, labels,
                width, height, physicalPage, initialKey);
        var printedFingerThrees = new ArrayList<>(decoded.printedFingeringThreeOwners());
        var provedDyadAccidentals = new ArrayList<>(decoded.provedDyadAccidentals());
        byte[] rhythmRaster = decoderGray == null ? gray : decoderGray;
        var fingered = ClosedSixteenthDyads.restoreOpposedFingeringQuartets(
                score, initialMeter, rhythmRaster, gray, width, height, physicalPage, printedFingerThrees::add);
        if (fingered != score) {
            score = PrintedAccidentalSpineRecovery.reconcileSpacedThirds(
                    fingered.withClockContextSource(score.clockContextSource()), labels, gray, rhythmRaster,
                    width, height, provedDyadAccidentals::add);
        }
        score =
                ClosedDottedSixteenths.applyClosedOctaves(
                        score,
                        initialMeter,
                        decoderGray == null ? gray : decoderGray,
                        width,
                        height);
        score = ClosingTripletRhythm.apply(
                score, initialMeter, labels, decoderGray == null ? gray : decoderGray, width, height);
        float[] beats = measureBeats(
                score.measures().size(), initialMeter.quarterBeats(), score.meterChanges());
        if (beats.length > 0) {
            double pickup = ScoreOpeningDuration.provedQuarterBeats(score, beats[0]);
            if (Double.isFinite(pickup)) beats[0] = (float) pickup;
            double closing =
                    ScoreOpeningDuration.provedClosingQuarterBeats(score, beats[beats.length - 1]);
            if (Double.isFinite(closing)) beats[beats.length - 1] = (float) closing;
        }
        boolean[] cadences = PrintedCadenceDurations.apply(score, beats);
        ClosedBeamedHalfBar.apply(
                score,
                initialMeter,
                decoderGray == null ? gray : decoderGray,
                width,
                height,
                beats);
        ClosedEarlyTripleDyads.apply(
                score,
                initialMeter,
                decoderGray == null ? gray : decoderGray,
                width,
                height,
                beats);
        IsolatedDottedHalfCadence.apply(score, labels, decoderGray == null ? gray : decoderGray,
                width, height, beats, cadences);
        PrintedFiveBeatMeasure.apply(
                score, decoderGray == null ? gray : decoderGray, gray, width, height, beats);
        PrintedOpeningSixteenthExamples.apply(
                score, labels, decoderGray == null ? gray : decoderGray, gray, width, height, beats);
        int[] keys = measureKeys(beats.length, initialKey, score.keyChanges());
        var wrapped =
                WrappedTripletMeasure.capture(
                        score,
                        initialMeter,
                        decoderGray == null ? gray : decoderGray,
                        gray,
                        width,
                        height,
                        beats,
                        labels,
                        keys,
                        annotations.words().stream().map(SheetInterpreter.Word::internal).toList());

        wrapped = WrappedSixteenthMeasure.capture(
                wrapped, initialMeter, decoderGray == null ? gray : decoderGray,
                width, height, beats, labels, keys);
        score = wrapped.score();
        var tiedCadence = TiedQuarterCadence.capture(score, initialMeter,
                decoderGray == null ? gray : decoderGray, width, height, beats);
        score = ClosedLateTripleDyads.normalize(
                tiedCadence.score(), initialMeter, decoderGray == null ? gray : decoderGray, width, height);
        score = OwnedTertiaryBeamlets.applyInteriorRhythm(
                score, gray, decoderGray == null ? gray : decoderGray, width, height);
        score = ClosedDottedSixteenths.restoreTripleDottedOctaveClock(
                score, initialMeter, rhythmRaster, gray, width, height);
        float[] lateDyadOnsets = ClosedLateTripleDyads.place(
                score, initialMeter, decoderGray == null ? gray : decoderGray, width, height, beats, cadences);
        var harmonicQuarterOnsets =
                ClosedDoubleHarmonicQuarters.writtenOnsets(
                        score, decoderGray == null ? gray : decoderGray, gray, width, height, beats);
        var closedHarmonicOnsets = ClosedHarmonicQuarterCadence.onsets(score);
        var harmonicPitches = ArtificialHarmonics.resolveThirdPitches(
                labels, decoderGray == null ? gray : decoderGray, width, height, score, initialKey);
        int sharedThird = TerminalSharedArtificialHarmonics.stoppedThird(score);
        double[] starts = new double[beats.length + 1];
        for (int i = 0; i < beats.length; i++) starts[i + 1] = starts[i] + beats[i];
        var events = new ArrayList<Map<String, Object>>();
        try (var timing = ScoreNoteTiming.beginTimingSession(score)) {
            for (int noteIndex = 0; noteIndex < score.notes().size(); noteIndex++) {
                var note = score.notes().get(noteIndex);
                int bar = note.measureIndex();
                var region = score.measures().get(bar);
                var identity = noteIdentity(note, keys[bar]);
                var harmonic = harmonicPitches.get(note);
                if (harmonic != null) {
                    identity.put("writtenMidi", harmonic.writtenMidi());
                    identity.put("harmonicTouchMidi", harmonic.touchMidi());
                    identity.put("midi", harmonic.soundingMidi());
                }
                if (bar == beats.length - 1 && cadences[bar]
                        && note.harmonicSemitones() == 28 && note.octaveShift() == 0
                        && (noteIndex == sharedThird || standardViolin && note.openStringTouch() != null)) {
                    int written = ScoreTiePitchGuard.writtenMidi(
                            note.diatonicPitchIdentity(), note.writtenAccidental(), keys[bar]);
                    int touch = ScoreTiePitchGuard.writtenMidi(
                            note.diatonicPitchIdentity() + 2, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, keys[bar]);
                    if (touch - written == 4 && written + 28 <= 127) {
                        identity.put("writtenMidi", written);
                        identity.put("harmonicTouchMidi", touch);
                        identity.put("artificialHarmonicThird", true);
                        identity.put("midi", written + 28);
                    }
                }
                boolean guessed = (Boolean) identity.get("clefInferred");
                double duration =
                        ScoreNoteTiming.resolvedWrittenDurationBeats(
                                note, score.notes(), beats[bar]);
                var wrappedTiming = wrapped.written()[noteIndex];
                var tiedCadenceTiming = tiedCadence.written()[noteIndex];
                if (wrappedTiming != null) duration = wrappedTiming.duration();
                if (tiedCadenceTiming != null) duration = tiedCadenceTiming.duration();
                boolean estimated = !Double.isFinite(duration) || duration <= 0;
                if (estimated && note.kind() == ScoreNoteEvent.Kind.UNPITCHED)
                    throw new IOException(
                            "An unpitched note needs a finite positive written duration");
                if (estimated) duration = .5;
                var event = new LinkedHashMap<String, Object>();
                event.put("measureIndex", bar);
                event.put("staffIndex", note.staffIndex());
                event.put("staffCount", note.staffCount());
                if (note.kind() == ScoreNoteEvent.Kind.UNPITCHED)
                    event.put("sourceNoteIndex", noteIndex);
                if (note.kind() == ScoreNoteEvent.Kind.PITCHED && note.octaveShift() != 0)
                    event.put("octaveShift", note.octaveShift());
                if (note.kind() == ScoreNoteEvent.Kind.PITCHED
                        && note.boundaryTies() != 0
                        && !guessed) {
                    event.put("boundaryTies", note.boundaryTies());
                    if (!TabTieIdentity.typed(note.boundaryTies())) {
                        event.put(
                                "boundaryPitch",
                                note.diatonicPitchIdentity() + note.octaveShift() * 7);
                        event.put("boundaryAccidental", note.writtenAccidental());
                    }
                    event.put("sourceNoteIndex", noteIndex);
                }
                event.putAll(identity);
                double startBeat =
                        starts[bar]
                                + (wrappedTiming != null ? wrappedTiming.onset()
                                  : tiedCadenceTiming != null ? tiedCadenceTiming.onset() : cadences[bar] ? 0
                                        : ScoreNoteTiming.beatInMeasure(note, score.notes(), beats[bar]));
                Integer harmonicOnset = harmonicQuarterOnsets.get(noteIndex);
                if (harmonicOnset != null) startBeat = starts[bar] + harmonicOnset;
                Double closedHarmonicOnset = closedHarmonicOnsets.get(noteIndex);
                if (closedHarmonicOnset != null) startBeat = starts[bar] + closedHarmonicOnset;
                if (lateDyadOnsets.length > 0 && Float.isFinite(lateDyadOnsets[noteIndex]))
                    startBeat = starts[bar] + lateDyadOnsets[noteIndex];
                if (note.kind() == ScoreNoteEvent.Kind.UNPITCHED
                        && (!Double.isFinite(startBeat)
                                || startBeat < 0
                                || !Double.isFinite(startBeat + duration)))
                    throw new IOException(
                            "An unpitched note needs a finite nonnegative written clock");
                event.put("startBeat", startBeat);
                if (NoteOrnament.tremoloBeams(note.articulations()) > 0)
                    event.put("tremoloBeats", NoteOrnament.tremoloBeats(note.articulations()));
                int guitar = note.articulations() & TabEffect.ALL;
                if (guitar != 0) {
                    var effect = new LinkedHashMap<String, Object>();
                    int kind = TabEffect.kind(guitar);
                    effect.put("type", TabEffect.name(guitar));
                    if (kind <= TabEffect.TAP)
                        effect.put("semitones", kind == 0 ? 0 : TabEffect.delta(guitar));
                    else effect.put("semitones", TabEffect.semitones(guitar));
                    effect.put("vibrato", (guitar & TabEffect.VIBRATO) != 0);
                    effect.put("palmMute", (guitar & TabEffect.PALM_MUTE) != 0);
                    if (kind == TabEffect.STRUM_DOWN || kind == TabEffect.STRUM_UP)
                        effect.put("strumIndex", TabEffect.strumIndex(guitar));
                    event.put("guitarEffect", effect);
                }
                event.put("durationBeats", duration);
                event.put("durationFallback", estimated);
                if (!cadences[bar]
                        && ScoreNoteTiming.hasImplicitDottedTriplet(note, score.notes(), beats[bar])) {
                    event.put("tupletActualNotes", 3);
                    event.put("tupletNormalNotes", 2);
                }
                event.put("tiedFromPrevious", note.tiedFromPrevious());
                event.put(
                        "x",
                        (region.left()
                                        + note.positionInMeasure()
                                                * (region.right() - region.left()))
                                * width);
                event.put("y", note.pageY() * height);
                events.add(event);
            }
            attachWrittenPlacements(score.notes(), events, beats, cadences);
            if (score.notes().stream().anyMatch(n -> (n.articulations() & NoteOrnament.GRACE) != 0))
                for (int i = 0; i < events.size(); i++) {
                    var wrappedHead = wrapped.written()[i];
                    var cadenceHead = tiedCadence.written()[i];
                    if (wrappedHead == null && cadenceHead == null) continue;
                    double onset = wrappedHead != null ? wrappedHead.onset() : cadenceHead.onset();
                    double duration = wrappedHead != null ? wrappedHead.duration() : cadenceHead.duration();
                    events.get(i).put("writtenPlacement", new ScoreNoteTiming.WrittenPlacement(
                            onset, duration, -1, -1, false, 0));
                }
            for (int i = 0; i < lateDyadOnsets.length; i++) {
                if (Float.isFinite(lateDyadOnsets[i]))
                    events.get(i).put("writtenPlacement", new ScoreNoteTiming.WrittenPlacement(
                            lateDyadOnsets[i], .25, -1, -1, false, 0));
            }
        }
        HarmonicBeamBarline.placeTails(
                decoded.harmonicTails(), score.notes(), events, beats, starts);
        if (standardViolin)
            PenultimateHarmonicQuarterClock.place(
                    score,
                    events,
                    decoderGray == null ? gray : decoderGray,
                    gray,
                    width,
                    height,
                    beats,
                    starts);
        attachNotePerformance(score.notes(), events);
        if (standardViolin) {
            score = ClosedStoppedAndOpenHarmonicQuarters.recover(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, beats, keys, starts);
            var mixed = ClosedMixedHarmonicQuarters.capture(score, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, beats, keys);
            score = ClosedMixedHarmonicQuarters.apply(score, events, mixed, starts, width, height);
            score = ClosedOpenAndStoppedHarmonicQuarters.recover(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    labels, gray, decoderGray == null ? gray : decoderGray,
                    width, height, beats, keys, starts);
            score = UpstemCompoundHarmonicQuarters.retain(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
            ClosedUpstemHarmonicChange.retainRoles(score, events, starts, keys);
            ClosedDownstemCompoundQuarters.retainRoles(score, events, starts);
            var recovered = OpenStringHarmonics.recover(score, initialKey);
            var compoundFourths = OpenStringHarmonics.captureFourthPartners(
                    score, gray, decoderGray == null ? gray : decoderGray, width, height, keys, recovered);
            if (!compoundFourths.isEmpty()) {
                var changed = new ArrayList<>(score.notes());
                for (var partner : compoundFourths.entrySet()) {
                    int owner = partner.getKey();
                    var base = partner.getValue();
                    var identity = noteIdentity(base, keys[base.measureIndex()]);
                    var event = events.get(owner);
                    if (!Boolean.TRUE.equals(identity.get("artificialHarmonicFourth"))
                            || !identity.get("midi").equals(event.get("midi"))
                            || !Integer.valueOf(owner).equals(event.get("sourceNoteIndex"))
                            || !completeCompoundQuarter(event, starts[base.measureIndex()],
                                    starts[base.measureIndex() + 1])) continue;
                    event.remove("octaveShift");
                    event.putAll(identity);
                    event.put("compoundHarmonicFourth", true);
                    changed.set(owner, base);
                }
                var owners = java.util.stream.IntStream.range(0, changed.size()).boxed().toList();
                score = score.withNotes(changed, owners).withClockContextSource(score.clockContextSource());
            }
            score = appendOpenStringHarmonics(score, events, recovered, height);
            score = UpstemCompoundHarmonicQuarters.recoverOpeningTouchAlias(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
            score = UpstemCompoundHarmonicQuarters.recoverClosingTouchAlias(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
            score = OpeningHarmonicQuarters.recover(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, beats, keys, starts);
        }
        var hollow = tabs.isEmpty() ? TerminalOpenHarmonicHeads.capture(score, labels,
                decoderGray == null ? gray : decoderGray, gray, width, height, beats,
                annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                initialKey, standardViolin) : List.<TerminalOpenHarmonicHeads.Chord>of();
        if (standardViolin && !hollow.isEmpty()) {
            var notes = new ArrayList<>(score.notes());
            var owners = new ArrayList<>(java.util.stream.IntStream.range(0,notes.size()).boxed().toList());
            var archived = new ArrayList<TerminalOpenHarmonicHeads.Chord>();
            for (var chord : hollow) {
                int bar = chord.measureIndex();
                var heads = new ArrayList<TerminalOpenHarmonicHeads.Head>();
                var region = score.measures().get(bar);
                for (var written : chord.heads()) {
                    int index = notes.size();
                    float position = (written.x()/width-region.left())/(region.right()-region.left());
                    float y = written.y()/height;
                    var note = new ScoreNoteEvent(bar,position,written.staffStep(),0,1,y,
                            false,0,0,ScoreNoteEvent.ACCIDENTAL_FROM_KEY,1,1,0,0,
                            ScoreNoteEvent.CLEF_TREBLE).withStemDirection(1);
                    var event = new LinkedHashMap<String,Object>();
                    event.put("measureIndex",bar);
                    event.put("staffIndex",0);
                    event.put("staffCount",1);
                    event.put("sourceNoteIndex",index);
                    event.put("midi",written.soundingMidi());
                    event.put("writtenMidi",written.writtenMidi());
                    event.put("harmonicTouchMidi",written.writtenMidi());
                    event.put("startBeat",starts[bar]+chord.quarterBeatOffset());
                    event.put("durationBeats",1);
                    event.put("clefInferred",false);
                    event.put("durationFallback",false);
                    event.put("tiedFromPrevious",false);
                    event.put("x",written.x());
                    event.put("y",written.y());
                    events.add(event);
                    notes.add(note);
                    owners.add(-1);
                    heads.add(new TerminalOpenHarmonicHeads.Head(written.staffStep(),written.writtenMidi(),
                            written.x(),written.y(),written.soundingMidi(),index));
                }
                archived.add(new TerminalOpenHarmonicHeads.Chord(bar,chord.quarterBeatOffset(),List.copyOf(heads)));
            }
            var context = score.clockContextSource().withNoteCount(notes.size());
            score = score.withNotes(notes,owners).withClockContextSource(context);
            hollow = List.copyOf(archived);
        }
        var beamedHarmonicAccidentals = new ArrayList<SourceLocalAccidental.PrintedMark>();
        if (standardViolin) {
            ClosedDoubleHarmonicQuarters.retainHeaderedStackRoles(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    labels, gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys,
                    beamedHarmonicAccidentals);
            ClosedDoubleHarmonicQuarters.retainMixedStemStackRoles(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    labels, gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
            score = ClosedDoubleHarmonicQuarters.retainClosingDownstemRoles(score, events, labels,
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys,
                    beamedHarmonicAccidentals);
            score = ClosedDoubleHarmonicQuarters.retainClosedFourthPairs(score, events, labels,
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
            score = ClosedDownstemCompoundQuarters.recoverPenultimateMixed(
                    score, events, labels, gray, decoderGray == null ? gray : decoderGray,
                    width, height, beats, starts, keys, beamedHarmonicAccidentals);
            score = ClosedDownstemCompoundQuarters.recoverPenultimateUpperOpen(
                    score, events, labels, gray, decoderGray == null ? gray : decoderGray,
                    width, height, beats, starts, keys);
            score = ClosedDoubleHarmonicQuarters.retainUpstemRoles(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
        }
        if (standardViolin)
            score = BeamedArtificialFourths.apply(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height,
                    starts, keys, physicalPage, decoded.printedOctaveSpans(), beamedHarmonicAccidentals,
                    decoded.beamedTouches());
        if (standardViolin)
            score = BeamedArtificialFourths.retainClosedTripletFourths(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, starts, keys, physicalPage,
                    decoded.printedOctaveSpans(),
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList());
        if (standardViolin)
            score = BeamedArtificialFourths.retainPairedTouches(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, keys, decoded.beamedTouches());
        if (standardViolin)
            score = BeamedOpenStringHarmonics.capture(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
        if (standardViolin)
            score = BeamedArtificialFourths.retainDottedBeams(score, events, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, starts, keys, physicalPage,
                    decoded.printedOctaveSpans(), beamedHarmonicAccidentals);
        if (standardViolin)
            score = ClosedBeamedOpenStringRun.capture(score, events, physicalPage, labels, gray,
                    decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
        score = ClosedSixteenthDyads.captureWrittenOctaveQuartets(
                score, events, gray, decoderGray == null ? gray : decoderGray, labels,
                width, height, physicalPage, beats, starts);
        if (standardViolin)
            score = ClosedDoubleHarmonicQuarters.retainReverseTerminalStackRoles(score, events,
                    annotations.words().stream().map(SheetInterpreter.Word::internal).toList(),
                    gray, decoderGray == null ? gray : decoderGray, width, height, beats, starts, keys);
        ClosedDoubleHarmonicQuarters.retainOpposedThirdRoles(score, events, width, height, beats, starts, keys);
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1);
        result.put("width", width);
        result.put("height", height);
        var ledgerExpressions = LedgerThirdExpressions.capture(
                score, gray, decoderGray == null ? gray : decoderGray, width, height, physicalPage);
        score = ledgerExpressions.score();
        result.put("score", score);
        result.put("events", events);
        result.put("tablatureWarnings", tabWarnings);
        result.put("measureBeats", beats);
        var printedOctaves = new ArrayList<Object>();
        for (var span : retainedStaffOctaves(decoded.printedOctaveSpans(), events, wrapped.continuations()))
            printedOctaves.add(new PrintedOctaveFragments.CompleteSpan(span));
        printedOctaves.addAll(PrintedOctaveFragments.retain(
                decoded.printedOctaveSpans(), events, wrapped.continuations(), beats));
        if (!printedOctaves.isEmpty()) result.put("printedOctaveSpans", printedOctaves);
        if (!hollow.isEmpty()) result.put("printedOpenHarmonicChords", hollow);
        var accidentals = decoded.accidentalSource().printedMarks(
                decoded.originalNotes(), score.notes(), decoded.accidentalMeasures());
        accidentals =
                SourceLocalAccidental.includeClosedTripletSharps(
                        accidentals, decoded.provedSharpOwners(), score.notes());
        accidentals = SourceLocalAccidental.includeProvedLocalMarks(
                accidentals, decoded.provedDoubleSharpOwners(), score.notes(),
                ScoreNoteEvent.ACCIDENTAL_DOUBLE_SHARP);
        accidentals = SourceLocalAccidental.includeProvedLocalMarks(
                accidentals, provedDyadAccidentals, score.notes());
        accidentals = SourceLocalAccidental.retainBeamedSharpRestatements(
                accidentals, score.notes(), score.measures(), gray,
                decoderGray == null ? gray : decoderGray, width, height);
        accidentals = SourceLocalAccidental.retainLedgerSixthNaturals(
                accidentals, score.notes(), score.measures(), gray,
                decoderGray == null ? gray : decoderGray, width, height);
        accidentals = SourceLocalAccidental.retainBeamedOctaveAccidentals(
                accidentals, score.notes(), score.measures(), labels, gray,
                decoderGray == null ? gray : decoderGray, width, height);
        accidentals = SourceLocalAccidental.retainSingleStaffTripletFlats(
                accidentals, score.notes(), score.measures(), labels, gray,
                decoderGray == null ? gray : decoderGray, width, height);
        if (!beamedHarmonicAccidentals.isEmpty() || sharedThird >= 0 || events.stream().anyMatch(
                event -> Boolean.TRUE.equals(event.get("mixedHarmonicQuarter")))) {
            var marks = new java.util.TreeMap<Integer, SourceLocalAccidental.PrintedMark>();
            for (var mark : accidentals) marks.put(mark.noteIndex(), mark);
            for (var mark : beamedHarmonicAccidentals) marks.putIfAbsent(mark.noteIndex(), mark);
            for (int i = 0; i < events.size(); i++) {
                if (i != sharedThird && !Boolean.TRUE.equals(events.get(i).get("mixedHarmonicQuarter"))) continue;
                int sign = score.notes().get(i).writtenAccidental();
                if (sign != ScoreNoteEvent.ACCIDENTAL_FROM_KEY)
                    marks.put(i, new SourceLocalAccidental.PrintedMark(i, sign));
            }
            accidentals = List.copyOf(marks.values());
        }
        if (tabs.isEmpty())
            accidentals = PrintedAccidentalSpineRecovery.retain(
                    accidentals, score, labels, gray, decoderGray, width, height);
        if (tabs.isEmpty())
            accidentals = ClosedOctavePrintedMarks.naturals(
                    accidentals, score, gray, decoderGray == null ? gray : decoderGray, width, height);
        if (!accidentals.isEmpty()) {
            result.put("printedAccidentals", accidentals.stream().map(mark -> {
                var value = new java.util.LinkedHashMap<String, Object>();
                value.put("noteIndex", mark.noteIndex());
                value.put("accidental", mark.accidental());
                if (Boolean.TRUE.equals(mark.naturalPrefix())) value.put("naturalPrefix", true);
                return value;
            }).toList());
        }
        if (!wrapped.continuations().isEmpty())
            result.put("printedMeasureContinuations", wrapped.continuations());
        var barlineStyles = PrintedBarlineStyles.capture(
                decoderGray == null ? gray : decoderGray, gray, width, height, score.measures(),
                wrapped.continuations());
        if (!barlineStyles.isEmpty()) result.put("printedBarlineStyles", barlineStyles);
        if (tabs.isEmpty()) {
            score = IncompleteDownstemQuartets.complete(score,
                    decoderGray == null ? gray : decoderGray, gray, width, height);
            score = ClosedDottedSixteenths.completeDottedQuartetBeams(score,
                    new ScoreMeterMap(initialMeter.quarterBeats(), score.meterChanges()),
                    gray, decoderGray == null ? gray : decoderGray, width, height);
            var printedAccidentalOwners = new java.util.HashSet<Integer>();
            for (var mark : accidentals)
                printedAccidentalOwners.add(mark.noteIndex());
            score = PrintedDottedPairSlurs.apply(score, gray, decoderGray == null ? gray : decoderGray,
                    width, height, printedAccidentalOwners::contains);
            result.put("score", score);
            var fingerings = PrintedFingeringDigits.capture(
                    gray, decoderGray == null ? gray : decoderGray, width, height,
                    annotations.words(), score, events);
            fingerings = StaffOverlaidBeamFingering.include(
                    fingerings, gray, decoderGray == null ? gray : decoderGray, width, height,
                    annotations.words(), score);
            fingerings = MeasuredStaffFingerings.retain(
                    fingerings, score, labels, gray, decoderGray == null ? gray : decoderGray, width, height);
            var parenthetical = ParentheticalFingerings.capture(fingerings, score, annotations.words(),
                    labels, gray, decoderGray == null ? gray : decoderGray, width, height);
            fingerings = RuledTripletFingeringAlias.attach(
                    parenthetical.marks(), printedFingerThrees, score.notes());
            fingerings = ClosedOctavePrintedMarks.fingerings(fingerings, score, gray,
                    decoderGray == null ? gray : decoderGray, width, height, annotations.words());
            if (!ledgerExpressions.fingerings().isEmpty()) {
                var combined = new ArrayList<>(fingerings);
                for (var proved : ledgerExpressions.fingerings()) {
                    combined.removeIf(mark -> mark.noteIndex() == proved.noteIndex()
                            && mark.placement() == proved.placement());
                    combined.add(proved);
                }
                fingerings = List.copyOf(combined);
            }
            var extraTwos = ParentheticalFingerings.staffCrossedTwos(
                    parenthetical, fingerings, score, gray, decoderGray == null ? gray : decoderGray,
                    width, height);
            var retained = new ArrayList<>(fingerings);
            retained.addAll(extraTwos);
            ClosedDottedSixteenths.includeDottedPairTerminalDigits(retained, score,
                    new ScoreMeterMap(initialMeter.quarterBeats(), score.meterChanges()),
                    gray, decoderGray == null ? gray : decoderGray, width, height);
            if (!fingerings.isEmpty()) {
                PrintedFingeringDigits.includeDottedTripletTerminalDigits(
                        retained, score, events, gray, decoderGray == null ? gray : decoderGray,
                        width, height);
                var withTerminalFingers = WrappedEighthTriplets.retainTerminalFingerTriplets(
                        score, fingerings, gray, decoderGray == null ? gray : decoderGray, width, height);
                result.put("score", PrintedFingeringDigits.retainUnnumberedHighLedgerRuns(
                        withTerminalFingers, fingerings, gray, decoderGray == null ? gray : decoderGray, width, height,
                        java.util.Set.copyOf(wrapped.continuations())));
            }
            var floorMeter = new ScoreMeterMap(initialMeter.quarterBeats(), score.meterChanges());
            ClosedDottedSixteenths.includeDottedPairFloorDigits(retained, score, floorMeter,
                    gray, decoderGray == null ? gray : decoderGray, width, height);
            PrintedFingeringDigits.includeBeamedFloorFingerings(retained, score, floorMeter,
                    gray, decoderGray == null ? gray : decoderGray, width, height);
            if (!retained.isEmpty()) result.put("printedFingerings", retained);
            var primaryFingerings = fingerings;
            if (!parenthetical.groups().isEmpty()) {
                result.put("printedFingeringGroups", parenthetical.groups().stream().map(group -> {
                    var row = new java.util.LinkedHashMap<String,Object>();
                    row.put("startNoteIndex", group.startNoteIndex());
                    row.put("endNoteIndex", group.endNoteIndex());
                    row.put("placement", group.placement());
                    if (primaryFingerings.stream().anyMatch(mark -> mark.noteIndex() == group.startNoteIndex()
                            && mark.placement() == group.placement() && mark.digit() != 3)
                            || extraTwos.stream().anyMatch(mark -> mark.noteIndex() == group.startNoteIndex()
                                    && mark.placement() == group.placement())) row.put("startDigit", 3);
                    return row;
                }).toList());
            }
            byte[] geometry = decoderGray == null ? gray : decoderGray;
            var marks = new ArrayList<PrintedRomanStrings.Mark>();
            if (standardViolin) {
                var romanScore = score;
                marks.addAll(PrintedRomanStrings.capture(geometry, gray, width, height, annotations.words(),
                        score, events, () -> OmrScoreInterpreter.techniqueStaffs(
                                labels, geometry, width, height, romanScore.measures())));
            }
            var openingMarks = OpeningRomanStrings.capture(gray, geometry, width, height,
                    annotations.words(), score, events);
            for (var mark : openingMarks) {
                if (marks.stream().noneMatch(previous -> previous.noteIndex() == mark.noteIndex()
                        && previous.placement().equals(mark.placement()))) marks.add(mark);
            }
            if (!marks.isEmpty()) result.put("printedStringMarks", marks);
            var stringContinuations = PrintedStringContinuations.capture(
                    gray, geometry, width, height, annotations.words(), openingMarks, score, events);
            if (!stringContinuations.isEmpty())
                result.put("printedStringContinuations", stringContinuations);
            var bowPositions = OpeningBowPositionDirections.capture(
                    gray, geometry, width, height, annotations.words(), score, events);
            if (!bowPositions.directions().isEmpty())
                result.put("printedBowPositionDirections", bowPositions.directions());
            if (!bowPositions.connectors().isEmpty())
                result.put("printedBowPositionConnectors", bowPositions.connectors());
        }
        result.put("totalBeats", starts[beats.length]);
        Files.writeString(Path.of(args[1]), json(result) + "\n", StandardCharsets.UTF_8);
    }

    static List<OctaveMarkDetector.StaffSpan> retainedStaffOctaves(
            List<OctaveMarkDetector.StaffSpan> spans, List<Map<String, Object>> events,
            List<Integer> continuations) {
        var result = new ArrayList<OctaveMarkDetector.StaffSpan>();
        for (var span : spans) {
            if (span.fragment() != null || continuations.contains(span.firstMeasureIndex())
                    || continuations.contains(span.lastMeasureIndex() + 1)) continue;
            var occupied = new java.util.BitSet();
            boolean partial = false;
            for (var event : events) {
                if (!(event.get("measureIndex") instanceof Integer measure)
                        || measure < span.firstMeasureIndex()
                        || measure > span.lastMeasureIndex()
                        || !Integer.valueOf(span.staffIndex()).equals(event.get("staffIndex"))
                        || !Integer.valueOf(span.staffCount()).equals(event.get("staffCount")))
                    continue;
                if (!(event.get("midi") instanceof Number)
                        || !Integer.valueOf(span.octaveShift()).equals(event.get("octaveShift"))) {
                    partial = true;
                    break;
                }
                occupied.set(measure);
            }
            if (!partial && occupied.nextClearBit(span.firstMeasureIndex()) > span.lastMeasureIndex())
                result.add(span);
        }
        return List.copyOf(result);
    }

    static boolean completeCompoundQuarter(Map<String, Object> event, double left, double right) {
        return Boolean.FALSE.equals(event.get("durationFallback"))
                && Boolean.FALSE.equals(event.get("tiedFromPrevious"))
                && !event.containsKey("writtenPlacement")
                && event.get("durationBeats") instanceof Number duration
                && duration.doubleValue() == 1
                && event.get("startBeat") instanceof Number onset
                && left <= onset.doubleValue() && onset.doubleValue() + 1 <= right;
    }

    private static ScorePageInterpretation appendOpenStringHarmonics(
            ScorePageInterpretation score, List<Map<String, Object>> events,
            List<OpenStringHarmonics.Recovery> recovered, int height) {
        if (recovered.isEmpty()) return score;
        var notes = new ArrayList<>(score.notes());
        var owners = new ArrayList<>(java.util.stream.IntStream.range(0, notes.size()).boxed().toList());
        for (var harmonic : recovered) {
            var anchor = events.get(harmonic.owner());
            if (Boolean.TRUE.equals(anchor.get("durationFallback"))) continue;
            var note = harmonic.note();
            var event = new LinkedHashMap<String, Object>();
            event.put("measureIndex", note.measureIndex());
            event.put("staffIndex", note.staffIndex());
            event.put("staffCount", note.staffCount());
            event.put("sourceNoteIndex", notes.size());
            event.put("midi", harmonic.soundingMidi());
            event.put("writtenMidi", harmonic.touchMidi());
            event.put("harmonicTouchMidi", harmonic.touchMidi());
            event.put("openStringMidi", harmonic.openMidi());
            if (Boolean.TRUE.equals(anchor.get("compoundUpstemHarmonicBar"))) {
                event.put("compoundUpstemHarmonicBar", true);
                event.put("compoundHarmonicFingering", anchor.remove("compoundNaturalHarmonicFingering"));
            }
            if (Boolean.TRUE.equals(anchor.get("mixedHarmonicQuarter")))
                event.put("mixedHarmonicQuarter", true);
            event.put("clefInferred", false);
            event.put("startBeat", anchor.get("startBeat"));
            event.put("durationBeats", anchor.get("durationBeats"));
            event.put("durationFallback", false);
            event.put("tiedFromPrevious", false);
            event.put("x", anchor.get("x"));
            event.put("y", note.pageY() * height);
            if (anchor.containsKey("writtenPlacement"))
                event.put("writtenPlacement", anchor.get("writtenPlacement"));
            events.add(event);
            notes.add(note);
            owners.add(-1);
        }
        if (notes.size() == score.notes().size()) return score;
        return score.withNotes(notes, owners).withClockContextSource(
                score.clockContextSource().withNoteCount(notes.size()));
    }

    static Map<String, Object> noteIdentity(ScoreNoteEvent note, int key) throws IOException {
        int clef = note.clefBottomDiatonic();
        boolean guessed = clef == ScoreNoteEvent.CLEF_UNKNOWN;
        if (guessed)
            clef =
                    note.staffCount() > 1 && note.staffIndex() > 0
                            ? ScoreNoteEvent.CLEF_BASS
                            : ScoreNoteEvent.CLEF_TREBLE;
        if (note.kind() == ScoreNoteEvent.Kind.UNPITCHED) {
            if (clef != ScoreNoteEvent.CLEF_BASS
                    && clef != ScoreNoteEvent.CLEF_TREBLE
                    && clef != 22
                    && clef != 24
                    && clef != 25
                    && clef != 32
                    && clef != 37)
                throw new IOException("Unpitched display clef must be a supported retained clef");
            long display = (long) clef + note.staffStep();
            if (display < 0 || display > 69)
                throw new IOException("Unpitched display octave must be 0..9");
        }
        int diatonic = clef + note.staffStep(),
                letter = Math.floorMod(diatonic, 7),
                octave = Math.floorDiv(diatonic, 7);
        var identity = new LinkedHashMap<String, Object>();
        if (note.kind() == ScoreNoteEvent.Kind.UNPITCHED) {
            if (note.octaveShift() != 0)
                throw new IOException(
                        "Unpitched source octave shifts need explicit instrument semantics");
            int guitar = note.articulations() & TabEffect.ALL;
            boolean neutralDead = guitar == TabEffect.encode(TabEffect.DEAD, 0);
            if (note.tiedFromPrevious()
                    || note.boundaryTies() != 0
                    || (note.articulations() & NoteOrnament.GRACE) != 0
                    || NoteOrnament.type(note.articulations()) != NoteOrnament.NONE
                    || NoteOrnament.tremoloBeams(note.articulations()) != 0
                    || guitar != 0 && !neutralDead)
                throw new IOException(
                        "Unpitched source ties and tonal performance marks need explicit instrument"
                            + " semantics");
            if (octave < 0 || octave > 9)
                throw new IOException("Unpitched display octave must be 0..9");
            identity.put("kind", note.kind().name());
            identity.put("displayStep", "CDEFGAB".substring(letter, letter + 1));
            identity.put("displayOctave", octave);
            identity.put("clefBottomDiatonic", clef);
        } else {
            int accidental = note.writtenAccidental();
            if (accidental == ScoreNoteEvent.ACCIDENTAL_FROM_KEY) {
                accidental = 0;
                int[] order =
                        key >= 0
                                ? new int[] {3, 0, 4, 1, 5, 2, 6}
                                : new int[] {6, 2, 5, 1, 4, 0, 3};
                for (int i = 0; i < Math.abs(key); i++)
                    if (order[i] == letter) accidental = key > 0 ? 1 : -1;
            }
            int midi =
                    (octave + 1 + note.octaveShift()) * 12
                            + new int[] {0, 2, 4, 5, 7, 9, 11}[letter]
                            + ScoreNoteEvent.accidentalSemitones(accidental);
            if (note.harmonicSemitones() == 24 && note.octaveShift() == 0) {
                int touch = ScoreTiePitchGuard.writtenMidi(
                        diatonic + 3, ScoreNoteEvent.ACCIDENTAL_FROM_KEY, key);
                if (touch - midi == 5 && midi + 24 <= 127) {
                    identity.put("writtenMidi", midi);
                    identity.put("harmonicTouchMidi", touch);
                    identity.put("artificialHarmonicFourth", true);
                    if (note.beamCount() == 2 && note.unbeamedDurationBeats() == 0
                            && note.augmentationDots() == 0 && note.tupletDivisor() == 1
                            && note.stemDirection() != 0)
                        identity.put("beamedArtificialHarmonicFourth", true);
                    midi += 24;
                }
            }
            identity.put("midi", midi);
        }
        identity.put("clefInferred", guessed);
        return identity;
    }

    /** Optional source engraving context; existing event clocks remain the playback clock. */
    static void attachWrittenPlacements(
            List<ScoreNoteEvent> notes, List<Map<String, Object>> events, float[] beats) {
        attachWrittenPlacements(notes, events, beats, new boolean[beats.length]);
    }

    private static void attachWrittenPlacements(
            List<ScoreNoteEvent> notes, List<Map<String, Object>> events, float[] beats,
            boolean[] cadences) {
        if (notes.stream().noneMatch(n -> (n.articulations() & NoteOrnament.GRACE) != 0)) return;
        if (notes.size() != events.size())
            throw new IllegalArgumentException("Written placement needs one event per source head");
        for (int i = 0; i < notes.size(); i++) {
            var note = notes.get(i);
            if (cadences[note.measureIndex()]) {
                events.get(i).put("writtenPlacement", new ScoreNoteTiming.WrittenPlacement(
                        0, beats[note.measureIndex()], -1, -1, false, 0));
                continue;
            }
            var placement =
                    ScoreNoteTiming.writtenPlacement(note, notes, beats[note.measureIndex()]);
            if (placement.isPresent()) events.get(i).put("writtenPlacement", placement.get());
        }
    }

    static void attachNotePerformance(
            List<ScoreNoteEvent> notes, List<Map<String, Object>> events) {
        for (int index = 0; index < notes.size(); index++) {
            var note = notes.get(index);
            if (!events.get(index).containsKey("sourceNoteIndex"))
                events.get(index).put("sourceNoteIndex", index);
            if (note.tupletDivisor() != 1) {
                events.get(index).put("tupletActualNotes", note.tupletDivisor());
                events.get(index).put("tupletNormalNotes", note.tupletNormalNotes());
            }
            if (note.kind() == ScoreNoteEvent.Kind.UNPITCHED) continue;
            var target = GlissPitchTarget.next(note, notes);
            if (target != null && target.kind() == ScoreNoteEvent.Kind.UNPITCHED) continue;
            if (target == null) continue;
            var sourceEvent = events.get(index);
            var targetEvent = events.get(notes.indexOf(target));
            double end =
                    ((Number) sourceEvent.get("startBeat")).doubleValue()
                            + ((Number) sourceEvent.get("durationBeats")).doubleValue();
            if (Math.abs(end - ((Number) targetEvent.get("startBeat")).doubleValue()) <= .04)
                sourceEvent.put(
                        "glissando",
                        Map.of("style", "white_keys", "targetMidi", targetEvent.get("midi")));
        }
        attachStraightSlides(notes, events);
    }

    private static void attachStraightSlides(
            List<ScoreNoteEvent> notes, List<Map<String, Object>> events) {
        if (notes.size() != events.size()) return;
        var indices = new java.util.IdentityHashMap<ScoreNoteEvent, Integer>();
        var duplicate = new java.util.IdentityHashMap<ScoreNoteEvent, Boolean>();
        for (int i = 0; i < notes.size(); i++)
            if (indices.put(notes.get(i), i) != null) duplicate.put(notes.get(i), true);
        var pairs = new ArrayList<int[]>();
        var outgoing = new int[notes.size()];
        for (int target = 0; target < notes.size(); target++) {
            var prior = SlidePitchSource.previous(notes.get(target), notes);
            Integer source = indices.get(prior);
            if (source == null
                    || duplicate.containsKey(prior)
                    || duplicate.containsKey(notes.get(target))) continue;
            if (!slideEndpoint(notes.get(source), events.get(source), source)
                    || !slideEndpoint(notes.get(target), events.get(target), target)) continue;
            double end =
                    ((Number) events.get(source).get("startBeat")).doubleValue()
                            + ((Number) events.get(source).get("durationBeats")).doubleValue();
            double next = ((Number) events.get(target).get("startBeat")).doubleValue();
            if (Math.abs(end - next) > 8 * Math.max(Math.ulp(end), Math.ulp(next))) continue;
            pairs.add(new int[] {source, target});
            outgoing[source]++;
        }
        for (int[] pair : pairs)
            if (outgoing[pair[0]] == 1)
                events.get(pair[1])
                        .put(
                                "slide",
                                Map.of(
                                        "style",
                                        "continuous",
                                        "sourceNoteIndex",
                                        pair[0],
                                        "sourceMidi",
                                        events.get(pair[0]).get("midi")));
    }

    private static boolean slideEndpoint(
            ScoreNoteEvent note, Map<String, Object> event, int index) {
        if (note.kind() != ScoreNoteEvent.Kind.PITCHED
                || (note.articulations() & 32768) != 0
                || note.clefBottomDiatonic() == ScoreNoteEvent.CLEF_UNKNOWN
                || Boolean.TRUE.equals(event.get("clefInferred"))
                || !"PITCHED".equals(event.getOrDefault("kind", "PITCHED"))
                || !Boolean.FALSE.equals(event.get("durationFallback"))
                || event.containsKey("glissando")
                || event.containsKey("guitarEffect")) return false;
        if (!Integer.valueOf(note.measureIndex()).equals(event.get("measureIndex"))
                || !Integer.valueOf(note.staffIndex()).equals(event.get("staffIndex"))
                || !Integer.valueOf(note.staffCount()).equals(event.get("staffCount"))
                || (event.containsKey("sourceNoteIndex")
                        && !Integer.valueOf(index).equals(event.get("sourceNoteIndex"))))
            return false;
        if (!(event.get("midi") instanceof Integer midi)
                || midi < 0
                || midi > 127
                || !(event.get("startBeat") instanceof Number start)
                || !(event.get("durationBeats") instanceof Number duration)) return false;
        return Double.isFinite(start.doubleValue())
                && start.doubleValue() >= 0
                && Double.isFinite(duration.doubleValue())
                && duration.doubleValue() > 0;
    }

    static int[] measureKeys(int count, int initialKey, List<ScoreKeyChange> changes) {
        int[] keys = new int[count];
        // Keep the last applicable entry in source order, even for unsorted input.
        for (int index = 0; index < changes.size(); index++) {
            int measure = changes.get(index).measureIndex();
            if (measure < count) keys[measure] = index + 1;
        }
        int latest = 0;
        for (int measure = 0; measure < count; measure++) {
            latest = Math.max(latest, keys[measure]);
            keys[measure] = latest == 0 ? initialKey : changes.get(latest - 1).fifths();
        }
        return keys;
    }

    private static float[] measureBeats(
            int count, float initialBeats, List<ScoreMeterChange> changes) {
        float[] beats = new float[count];
        for (var change : changes) {
            int index = change.measureIndex();
            if (index < count) beats[index] = change.quarterBeats();
            else if (index > count) throw new IllegalArgumentException("Meter change outside page");
        }
        float current = initialBeats;
        for (int index = 0; index < count; index++) {
            // A zero entry means no change. Every ScoreMeterChange has positive duration.
            if (beats[index] != 0) current = beats[index];
            else beats[index] = current;
        }
        return beats;
    }

    private static List<SheetInterpreter.Word> readWords(DataInputStream in, int count)
            throws IOException {
        var words = new ArrayList<SheetInterpreter.Word>(count);
        byte[] text = new byte[0];
        for (int i = 0; i < count; i++) {
            int length = readCount(in);
            if (length > text.length) text = new byte[Math.max(length, text.length * 2)];
            in.readFully(text, 0, length);
            words.add(new SheetInterpreter.Word(
                    new String(text, 0, length, StandardCharsets.UTF_8),
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
        }
        return words;
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 10000) throw new IOException("Annotation limit exceeded");
        return count;
    }

    private static List<SheetInterpreter.NumberToken> readNumbers(DataInputStream in)
            throws IOException {
        int count = readCount(in);
        var result = new ArrayList<SheetInterpreter.NumberToken>(count);
        if (count == 0) return result;
        var record = ByteBuffer.allocate(6 * Integer.BYTES);
        for (int i = 0; i < count; i++) {
            in.readFully(record.array());
            record.clear();
            result.add(
                    new SheetInterpreter.NumberToken(
                            record.getInt(),
                            record.getFloat(),
                            record.getFloat(),
                            record.getFloat(),
                            record.getFloat(),
                            record.getFloat()));
        }
        return result;
    }

    private static final ClassValue<java.lang.reflect.RecordComponent[]> JSON_FIELDS =
            new ClassValue<>() {
                @Override
                protected java.lang.reflect.RecordComponent[] computeValue(Class<?> type) {
                    return type.getRecordComponents();
                }
            };

    static String json(Object value) throws Exception {
        var output = new StringBuilder();
        appendJson(output, value);
        return output.toString();
    }

    private static void appendJson(StringBuilder out, Object value) throws Exception {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' || c == '"') out.append('\\').append(c);
                else if (c < 32)
                    out.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                            .append(Character.forDigit(c & 15, 16));
                else out.append(c);
            }
            out.append('"');
        } else if (value instanceof Number n) {
            out.append(Double.isFinite(n.doubleValue()) ? n.toString() : "null");
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof ScorePlaybackDirection direction
                && direction.details().equals(ScorePlaybackDirection.Details.legacy())) {
            out.append("{\"measureBoundary\":").append(direction.measureBoundary())
                    .append(",\"kind\":").append(direction.kind().wireId()).append('}');
        } else if (value instanceof ScorePlaybackDirection.Kind kind) {
            out.append(kind.wireId());
        } else if (value instanceof java.util.Optional<?> optional) {
            appendJson(out, optional.orElse(null));
        } else if (value instanceof Enum<?> enumeration) {
            appendJson(out, enumeration.name());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                appendJson(out, entry.getKey().toString());
                out.append(':');
                appendJson(out, entry.getValue());
            }
            out.append('}');
        } else if (value.getClass().isRecord()) {
            out.append('{');
            boolean first = true;
            for (var component : JSON_FIELDS.get(value.getClass())) {
                String name = component.getName();
                // An omitted kind is the pitched default in the JSON contract.
                if (value instanceof ScoreNoteEvent note
                        && note.kind() == ScoreNoteEvent.Kind.PITCHED
                        && name.equals("kind")) continue;
                if (value instanceof ScoreBeamSource.Group group
                        && group.tupletMark() == null
                        && name.equals("tupletMark")) continue;
                if (value instanceof ScoreTechniqueChange technique
                        && technique.placement() == null && name.equals("placement")) continue;
                if (!first) out.append(',');
                first = false;
                appendJson(out, name);
                out.append(':');
                appendJson(out, component.getAccessor().invoke(value));
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (var item : items) {
                if (!first) out.append(',');
                first = false;
                appendJson(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int size = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < size; i++) {
                if (i > 0) out.append(',');
                appendJson(out, java.lang.reflect.Array.get(value, i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value " + value.getClass());
        }
    }
}
