# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Original decoded logical notes, testing the actual Java route and emitted MIDI."""
import copy
import struct
import tempfile
import unittest
from pathlib import Path
from sheet_music_recognition.navigation import project_navigation
from sheet_music_recognition.midi import write_midi


def note(beat, pitch, duration=1, tied=False):
    return dict(startBeat=beat, midi=pitch, durationBeats=duration, tiedFromPrevious=tied,
                staffIndex=0, staffCount=1)


def document(beats, notes, directions, tempos=()):
    return dict(pages=[dict(measureBeats=beats, totalBeats=sum(beats), events=notes,
        score=dict(playbackDirections=directions, tempoChanges=list(tempos)))])


def direction(boundary, kind):
    return dict(measureBoundary=boundary, kind=kind)


def final_tick(data):
    cursor,tick=22,0  # MThd + format0 MTrk header; writer emits explicit statuses.
    def variable():
        nonlocal cursor
        value=0
        while True:
            byte=data[cursor];cursor+=1;value=(value<<7)|(byte&127)
            if byte<128:return value
    while cursor<len(data):
        tick+=variable();status=data[cursor];cursor+=1
        if status==255:
            kind=data[cursor];cursor+=1;length=variable();cursor+=length
            if kind==47:return tick
        else:cursor+=1 if status&240 in (192,208) else 2
    raise AssertionError('Missing MIDI end of track')


def note_intervals(data):
    cursor,tick=22,0
    active,intervals={},[]
    def variable():
        nonlocal cursor
        value=0
        while True:
            byte=data[cursor];cursor+=1;value=(value<<7)|(byte&127)
            if byte<128:return value
    while cursor<len(data):
        tick+=variable();status=data[cursor];cursor+=1
        if status==255:
            kind=data[cursor];cursor+=1;length=variable();cursor+=length
            if kind==47:break
            continue
        kind=status&240;length=1 if kind in (192,208) else 2
        payload=data[cursor:cursor+length];cursor+=length
        if kind not in (128,144):continue
        key=(status&15,payload[0])
        if kind==144 and payload[1]:
            if key in active:raise AssertionError('Overlapping MIDI note on one channel')
            active[key]=tick
        else:
            if key not in active:raise AssertionError('Release without MIDI attack')
            intervals.append((payload[0],active.pop(key),tick))
    if active:raise AssertionError('Unreleased MIDI notes')
    return sorted(intervals,key=lambda row:(row[1],row[0]))


class NavigationMidiTest(unittest.TestCase):
    def test_trill_uses_key_sharps_and_held_tie_with_independent_chord_tone(self):
        notes = [dict(note(at, pitch, duration, tied), measureIndex=bar, sourceNoteIndex=index)
                 for index, (at, pitch, duration, tied, bar) in enumerate(
                     ((0, 64, 2, False, 0), (0, 60, 2, False, 0), (2, 64, 1, True, 1)))]
        doc = document([2, 2], notes, [])
        doc['initialKeyFifths'] = 1
        doc['pages'][0]['score']['notes'] = [dict(
            measureIndex=n['measureIndex'], staffIndex=0, staffCount=1, staffStep=step,
            clefBottomDiatonic=30, positionInMeasure=0, writtenAccidental=2, articulations=flags)
            for n, step, flags in zip(notes, (0, -2, 0), (32, 0, 32), strict=True)]
        before = copy.deepcopy(doc)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'trill.mid'
            write_midi(doc, path)
            data = path.read_bytes()
        intervals = note_intervals(data)
        self.assertEqual([(60, 0, 960)], [row for row in intervals if row[0] == 60])
        pulses = [row for row in intervals if row[0] != 60]
        self.assertEqual([64, 66] * 9 + [64], [row[0] for row in pulses])
        self.assertEqual((0, 1440), (pulses[0][1], pulses[-1][2]))
        self.assertEqual([row[2] for row in pulses[:-1]], [row[1] for row in pulses[1:]])
        self.assertEqual(1920, final_tick(data))
        self.assertEqual(before, doc)

    def test_second_pass_only_lower_heads_keep_both_upper_attacks(self):
        notes = [dict(note(beat, midi, .5), measureIndex=0, sourceNoteIndex=i)
                 for i, (beat, midi) in enumerate(((0, 69), (0, 72), (2, 69), (2, 72)))]
        score = document([4], notes, [direction(0, 4), direction(1, 5)])
        raw = score['pages'][0]['score']
        raw['notes'] = [dict(measureIndex=0, staffIndex=0, staffCount=1,
                             positionInMeasure=position, staffStep=step)
                        for position in (.1, .6) for step in (3, 5)]
        source = 'printed-bottom-note-second-pass:v1'
        positions = [struct.unpack('>i', struct.pack('>f', x))[0] for x in (.1, .6)]
        raw['expressiveEvents'] = [dict(
            eventId=source+':0:0:0', kind='UNRESOLVED_DIRECTION', scope='NOTE',
            start=dict(measureIndex=0, quarterBeatOffset=0), end=dict(measureIndex=1, quarterBeatOffset=0),
            staffIndex=0, staffCount=1, targetEventId=','.join(f'{x}:3' for x in positions),
            evidence=[dict(sourceId=source)])]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'lower-heads.mid'
            write_midi(score, path)
            self.assertEqual([(72, 0, 240), (72, 960, 1200), (69, 1920, 2160),
                              (72, 1920, 2160), (69, 2880, 3120), (72, 2880, 3120)],
                             note_intervals(path.read_bytes()))

    def test_repeat_preserves_fractional_pickup_and_closing_spans(self):
        for first, last, intervals in (
            (1 / 3, 4, [(60, 0, 160), (62, 160, 2080), (60, 2080, 2240), (62, 2240, 4160)]),
            (4, 2 / 3, [(60, 0, 1920), (62, 1920, 2240), (60, 2240, 4160), (62, 4160, 4480)]),
            (.1875, 4, [(60, 0, 90), (62, 90, 2010), (60, 2010, 2100), (62, 2100, 4020)]),
        ):
            with self.subTest(first=first, last=last), tempfile.TemporaryDirectory() as folder:
                doc = document([first, last], [note(0, 60, first), note(first, 62, last)],
                               [direction(0, 4), direction(2, 5)])
                played = project_navigation(doc)['pages'][0]
                self.assertAlmostEqual(2 * (first + last), played['totalBeats'], places=12)
                path = Path(folder) / 'fractional-repeat.mid'
                write_midi(doc, path)
                data = path.read_bytes()
                self.assertEqual(intervals, note_intervals(data))
                self.assertEqual(intervals[-1][2], final_tick(data))

    def test_tied_marker_cannot_bridge_a_whole_silent_source_bar(self):
        doc=document([4,4,4],[note(0,60,4),note(8,60,4,True)],[])
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'silent-bar.mid';write_midi(doc,path)
            self.assertEqual([(60,0,1920),(60,3840,5760)],note_intervals(path.read_bytes()))
            self.assertEqual(5760,final_tick(path.read_bytes()))

    def test_partial_fine_emits_exact_pitch_attack_and_release_oracle(self):
        fine=dict(measureBoundary=0,kind=12,details=dict(eventId='fine',quarterBeatOffset=1.5))
        doc=document([4,4,4],[note(0,60),note(1,62),note(2,64),note(3,65),
                             note(4,67,4),note(8,69,4)],[direction(2,9),fine])
        expected=[(60,0,480),(62,480,960),(64,960,1440),(65,1440,1920),
                  (67,1920,3840),(60,3840,4320),(62,4320,4560)]
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'partial.mid';write_midi(doc,path)
            self.assertEqual(expected,note_intervals(path.read_bytes()))
            self.assertEqual(4560,final_tick(path.read_bytes()))

    def test_jittered_incoming_tie_cannot_attach_to_skipped_predecessor(self):
        doc=document([4,4,4],[note(0,60,4),note(4.02,60,3.98,True),note(8,60,4)],
                     [direction(1,4),direction(3,5)])
        notes=project_navigation(doc)['pages'][0]['events']
        self.assertEqual([False,True,False,False,False],
                         [n['tiedFromPrevious'] for n in notes])
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'incoming.mid';write_midi(doc,path)
            self.assertEqual(4,path.read_bytes().count(b'\x90\x3c\x50'))

    def test_internal_tie_remains_owned_on_each_return(self):
        doc=document([4,4,4],[note(0,60,4),note(4.02,60,1.98),
                             note(6,60,2,True),note(8,60,4)],
                     [direction(1,4),direction(3,5)])
        notes=project_navigation(doc)['pages'][0]['events']
        self.assertEqual([False,False,True,False,False,True,False],
                         [n['tiedFromPrevious'] for n in notes])
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'internal.mid';write_midi(doc,path)
            self.assertEqual(5,path.read_bytes().count(b'\x90\x3c\x50'))

    def test_partial_ending_and_trailing_rests_retain_exact_midi_extent(self):
        fine=dict(measureBoundary=0,kind=12,details=dict(eventId='fine',quarterBeatOffset=1.5))
        docs=[(document([4,4,4],[note(0,60)],[direction(2,9),fine]),4560),
              (document([4],[note(0,60)],[]),1920)]
        with tempfile.TemporaryDirectory() as folder:
            for index,(doc,expected) in enumerate(docs):
                path=Path(folder)/f'extent-{index}.mid';write_midi(doc,path)
                self.assertEqual(expected,final_tick(path.read_bytes()))

    def test_unlabeled_word_endings_use_the_same_engraved_passage(self):
        def evidence(identity, page, label):
            return dict(sourceId=identity, pageIndex=page, visualX=.2,
                        staffIndex=0, staffCount=1, printedText=label)

        def printed(bar, kind, label='', passes=(), end=None):
            group = 'printed-repeat:0:0:3'
            identity = f'{group}:{kind}:{bar}'
            return dict(measureBoundary=bar, kind=kind, details=dict(
                eventId=identity, repeatGroupId=group, totalPlays=3, passes=list(passes),
                end=None if end is None else dict(measureIndex=end, quarterBeatOffset=0),
                printedText=label, evidence=[evidence(identity, 0, label)]))

        def caption(bar, label):
            identity = f'unresolved-repeat-caption:1:{label}:{bar}'
            proof = 'printed-repeat-caption:v1:' + identity
            return dict(eventId=identity, kind='UNRESOLVED_DIRECTION', scope='UNRESOLVED',
                        start=dict(measureIndex=bar, quarterBeatOffset=0), end=None,
                        staffIndex=0, staffCount=1, strength='UNSPECIFIED', targetEventId=None,
                        qualifierText=label, evidence=[evidence(proof, 1, label)])

        pitches = [(0, 1, -1), (-3, -2, -1), (3, -1), (-2, -4)]
        notes = [dict(note(3 * bar + offset, 64 + pitch),
                      measureIndex=bar, sourceNoteIndex=index)
                 for index, (bar, offset, pitch) in enumerate(
                     (bar, offset, pitch)
                     for bar, cluster in enumerate(pitches)
                     for offset, pitch in enumerate(cluster))]
        roster = [dict(measureIndex=bar, staffStep=pitch, clefBottomDiatonic=30)
                  for bar, cluster in enumerate(pitches) for pitch in cluster]
        scan = document([3]*4, notes, [
            printed(0, 4), printed(3, 5),
            printed(2, 6, '1., 2.', (1, 2), 3), printed(3, 6, '3.', (3,), 4)
        ])['pages'][0]
        target = document([3]*4, notes, [])['pages'][0]
        scan['score'].update(firstMeasureNumber=1, notes=roster)
        target['score'].update(firstMeasureNumber=1, notes=roster, expressiveEvents=[
            caption(0, 'Repeat Start'), caption(2, 'Ending'),
            caption(3, 'Repeat End'), caption(3, 'Ending')])
        doc = dict(pages=[scan, target])
        routed = project_navigation(doc)
        expected = [0, 1, 2, 0, 1, 2, 0, 1, 3]
        for rank in (0, 1):
            self.assertEqual(expected, [row['start']['measureIndex']
                                        for row in routed['navigationOccurrences']
                                        if row['arrangementIndex'] == rank])
        self.assertEqual([], target['score']['playbackDirections'])

        incomplete = copy.deepcopy(doc)
        incomplete['pages'][1]['score']['expressiveEvents'].pop()
        unrouted = project_navigation(incomplete)
        self.assertEqual(12, unrouted['pages'][1]['totalBeats'])

    def test_alternate_arrangements_do_not_jump_into_each_other(self):
        first=document([4],[note(0,60)],[direction(1,7)])['pages'][0]
        second=document([4],[note(0,72)],[direction(1,7)])['pages'][0]
        first['score']['firstMeasureNumber']=1;second['score']['firstMeasureNumber']=1
        played=project_navigation(dict(pages=[first,second]))
        self.assertEqual([[60,60],[72,72]],[[n['midi'] for n in page['events']] for page in played['pages']])
        self.assertNotEqual(played['pages'][0]['events'][0]['sourceEventId'],played['pages'][1]['events'][0]['sourceEventId'])

    def test_actual_kernel_repeats_notes_and_midi_attacks_without_inference(self):
        doc = document([4,4,4], [note(0,60),note(4,62),note(8,64)],
                       [direction(0,4),direction(2,5)])
        before = copy.deepcopy(doc)
        played = project_navigation(doc)
        self.assertEqual([60,62,60,62,64], [n['midi'] for n in played['pages'][0]['events']])
        self.assertEqual(20, played['pages'][0]['totalBeats'])
        self.assertEqual(before, doc)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'original.mid'; write_midi(doc,path)
            self.assertEqual(2,path.read_bytes().count(b'\x90\x3c\x50'))

    def test_partial_fine_clips_sustain_and_does_not_invent_whole_bar(self):
        fine=dict(measureBoundary=0,kind=12,details=dict(eventId='fine',quarterBeatOffset=1.5))
        doc=document([4,4,4],[note(1,60,2)],[direction(2,9),fine])
        played=project_navigation(doc)['pages'][0]
        self.assertEqual(9.5,played['totalBeats'])
        self.assertEqual([2,.5],[n['durationBeats'] for n in played['events']])
        self.assertFalse(played['events'][-1]['tiedFromPrevious'])

    def test_return_into_sustained_note_rearticulates_only_at_actual_jump(self):
        doc=document([4,4],[note(0,60,8)],[direction(0,4),direction(2,5)])
        notes=project_navigation(doc)['pages'][0]['events']
        self.assertEqual([False,True,False,True],[n['tiedFromPrevious'] for n in notes])
        self.assertEqual([0,4,8,12],[n['startBeat'] for n in notes])
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'held.mid';write_midi(doc,path)
            self.assertEqual(2,path.read_bytes().count(b'\x90\x3c\x50'))

    def test_destination_restores_numeric_tempo_without_later_state_leaking_back(self):
        doc=document([4,4,4],[],[direction(0,4),direction(2,5)],
            [dict(measureIndex=1,positionInMeasure=0,bpm=60)])
        tempo=project_navigation(doc,120)['pages'][0]['score']['tempoChanges']
        self.assertEqual([120,60,120,60],[t['bpm'] for t in tempo])

    def test_cross_page_repeat_uses_one_actual_route(self):
        first=document([4],[note(0,60)],[direction(0,4)])['pages'][0]
        second=document([4],[note(0,62)],[direction(1,5)])['pages'][0]
        played=project_navigation(dict(pages=[first,second]))['pages'][0]
        self.assertEqual([60,62,60,62],[n['midi'] for n in played['events']])
        self.assertEqual(16,played['totalBeats'])

    def test_coda_omits_notes_but_keeps_route_time_and_tail_rests(self):
        doc=document([4]*10,[note(i*4,60+i) for i in range(10)],
            [direction(7,11),direction(3,1),direction(8,3)])
        played=project_navigation(doc)['pages'][0]
        self.assertEqual([60,61,62,63,64,65,66,60,61,62,68,69],[n['midi'] for n in played['events']])
        self.assertEqual(48,played['totalBeats'])

    def test_linear_export_does_not_call_java_or_change_input(self):
        doc=document([4],[note(0,60)],[])
        self.assertIs(doc,project_navigation(doc))


if __name__=='__main__': unittest.main()

class PrintedSecondPassMidiTest(unittest.TestCase):
    def test_cross_page_repeat_mutes_only_the_enclosed_first_visit(self):
        import struct

        def express(i, first, last, source, label='', positions=None):
            anchor = lambda measure: dict(measureIndex=measure, quarterBeatOffset=0)
            name = 'unresolved-printed-ending:' if 'ending' in source else 'unresolved-printed-repeat:'
            if positions is not None:
                name = source + ':'
            identity = f'{name}{i}:{first}'
            proof = source if positions is not None else source+identity
            return dict(eventId=identity, kind='UNRESOLVED_DIRECTION', scope='UNRESOLVED',
                        start=anchor(first), end=anchor(last) if last is not None else None,
                        staffIndex=0, staffCount=1, targetEventId=positions, strength='UNSPECIFIED',
                        qualifierText='', evidence=[dict(sourceId=proof, pageIndex=i,
                        visualX=.5, staffIndex=0, staffCount=1, printedText=label)])

        pos = (.125, .25, .75)
        bits = lambda x: str(struct.unpack('>i', struct.pack('>f', x))[0])
        owned = express(0, 1, 2, 'printed-second-pass-only:v1', 'second time only',
                        ','.join(map(bits, pos[:2])))
        events = [dict(note(4+i, 60+i), measureIndex=1, sourceNoteIndex=i) for i in range(3)]
        roster = [dict(measureIndex=1, staffIndex=0, staffCount=1, positionInMeasure=x) for x in pos]
        first = document([4]*4, events, [])['pages'][0]
        first['sourcePage'] = 0
        first['score'].update(firstMeasureNumber=1, notes=roster, expressiveEvents=[
            express(0, 1, None, 'printed-repeat-start:v1:'),
            express(0, 3, 4, 'printed-ending-span:v1:true:false:', '1.'), owned])
        second = document([4]*4, [], [])['pages'][0]
        second['sourcePage'] = 1
        second['score'].update(firstMeasureNumber=5, expressiveEvents=[
            express(1, 2, None, 'printed-repeat-end:v1:'),
            express(1, 2, 3, 'printed-ending-span:v1:true:true:', '2.')])
        result = project_navigation(dict(pages=[first, second]))
        self.assertEqual([(e['midi'], e['startBeat']) for e in result['pages'][0]['events']],
                         [(62, 6.), (60, 24.), (61, 25.), (62, 26.)])
        self.assertEqual(result['pages'][0]['totalBeats'], 40.)
        missing_page = project_navigation(dict(pages=[first, dict(second, sourcePage=2)]))
        self.assertEqual(missing_page['pages'][0]['events'], events)
