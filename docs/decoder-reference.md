# Java decoder reference

Start with the [Java API example](integration.md#java). For a separate local process, see the [decoder service](native-decoder.md).

## Input

The decoder accepts grayscale pixels and segmentation labels as unsigned byte values. It rejects invalid shapes, unknown labels, and pages over 20 million analysis pixels. It has no Android dependency.

To run the bundled segmentation model yourself, pass float32 `[1,3,320,320]` values from 0 to 255, repeating the same grayscale plane across all three channels. The model normalizes its input and returns int64 `[1,320,320]` class IDs. Use the analysis scale, tile stride, and overlap rules in the [model card](../models/MODEL_CARD.md).

## OCR

The standard `SheetInterpreter.analyze` overload uses supplied `Annotations` and bundled glyph templates. It does not start an OCR engine. For staff-local meter, dynamics, and ornaments, pass `new MusicalOcr(inference, meterFontMatcher)` as the sixth argument. The caller owns the `PortableOcr.Inference` instance and its thread safety. Errors propagate and explicit meter annotations take precedence.

`MeterFontMatcher` takes a cleaned ARGB crop, staff-line offset and spacing, and `java/assets/Bravura.otf`. `MusicalOcrEvidence` accepts a strong pair of digits or compatible partial OCR; it leaves conflicts unresolved.

For PDFs, the Python CLI also uses embedded tab text when available. See [tablature](guitar-tabs.md) for tuning and rhythm, and [tab effects](tab-gestures.md) for supported marks.

## Playback and navigation

| API | Use |
| --- | --- |
| `ScoreNavigationPlan.create(count, directions, meter)` | Build the playback route from repeats, endings, D.C./D.S., Fine, and Coda. To Coda and Fine only activate on the corresponding return. Returns skip repeats by default. Measure-repeat shorthand is reported without expansion. |
| `ScoreNavigationProjection.project` | Restore source key, tempo, and meter on a return and project dynamics and expressions. Supply explicit starting values. Requires whole-bar coverage and does not handle partial-bar jumps. |
| `ScoreNavigationPerformance.project` | Map a resolved tempo and hold timeline through the route, including partial bars and the tempo curve's phase on a return. |
| `ScoreNavigationNoteProjection.project` | Map resolved sounding intervals through the same route. Jumps clip notes; choose whether to reject, omit, or reattack a held note on entry. |
| `ScoreGainProjection.project(curves, plan, meter, sourceTimeline)` | Map resolved dynamics through the route using the same meter map. Lane IDs use `staffCount * 16 + staffIndex`; hairpins interpolate in active tempo time. |
| `ScoreMeterMap.fromPerformedDurations` | Keep exact quarter-beat lengths for performed segments, including partial bars. Use `quarterBeatsInMeasure` and `performedMeasureCount` to read this grid. |

A `ScorePlaybackDirection` carries offsets, source and destination IDs, passes, and return behavior. When joining pages, offset the musical anchors and keep printed source IDs separate from playback occurrences.

Wire values 0–3 are segno, to-coda, D.S. al Coda, and coda. Values 4–13 are repeat start, repeat end, ending, D.C., D.S., D.C. al Fine, D.S. al Fine, D.C. al Coda, Fine, and measure-repeat shorthand.

Dynamic records default missing `fixedTarget` to false and missing `sharedTiming` to `sharedStaffs`. When `fixedTarget` is true, a hairpin's `decibels` is the absolute endpoint for that lane. `sharedTiming` uses both staves' onset geometry independently of which staff receives the gain. Shared-staff broadcasts always use shared timing.

Python MIDI and MP3 can use the Java route from saved JSON. They reattack a held note when entering it after a jump and cannot reuse a tie from a skipped predecessor. MusicXML stays in score order. See [expressive previews](expressive-performance.md) for export behavior; these writers do not take a caller's resolved Java performance clock.

## Source identities and retained guides

Use `SheetInterpreter.analyze(labels, gray, width, height, annotations, ocr, physicalPageIndex)` to retain the zero-based source page. Older overloads leave the page unknown. `page.slurSource().spans()` contains confirmed slurs; `candidates()` contains arcs that still need musical context. Keep original note IDs when joining or editing pages. Once the assembled score has a written clock, grace ordering, sounding pitches, and tie ownership, `ScoreSlurClassification.resolve` can classify the remaining arcs. See [printed slurs](printed-slur-recognition.md).

Raw notes encode a double sharp as `writtenAccidental: 3`; `2` means no local accidental. A treble clef with an 8 above uses `clefBottomDiatonic: 37` (E5). MIDI pitches already include these adjustments. The grace bit in `articulations` is `NoteOrnament.GRACE` (32768). Preview grace groups borrow at most 0.25 quarter beats or a quarter of the principal note's duration, whichever is smaller.

| Guide version | Compatibility |
| --- | --- |
| 285 | Appends slur source data after the semantic sections. Confirmed-only data keeps the SLT1 layout; SLT2 adds a deferred arc section. The Python guide writer accepts both. |
| 291 | Adds attack-only bow technique values 6 (up-bow) and 7 (down-bow) to the existing five-field record. Older readers and writers reject these values. MusicXML writes the bow once per chord on its attack fragment. |
| 293 | Appends a visual-size byte after the note's attack-kind byte: 0 is `FULL`, 1 is `CUE`. Guide 293 notes are 81 bytes; guide 292 notes are 80 bytes. Older records read as `FULL`. MusicXML sets the note type's size attribute without silencing or retiming it. |

Do not write an older guide if doing so would lose source slurs, bow directions, or cue size. Persistent MusicXML directions require matching source note IDs and consistent note clocks; older JSON without the IDs can omit them.
