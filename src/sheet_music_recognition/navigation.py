# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Project decoded note events through the production Java route, without re-inference."""
import copy
import json
import math
import struct
import subprocess
import tempfile
import warnings
from bisect import bisect_left, bisect_right
from pathlib import Path
from .runtime import java_executable
from .semantic_wire import encode
from .slide_performance import mark_linear_slide_visits
from .typed_events import event_kind, validate_unpitched


def _bridge(data):
    jar = Path(__file__).with_name('interpreter.jar')
    if not jar.is_file():
        raise RuntimeError('Build the Java interpreter before exporting navigation')
    if len(data) > 64*1024*1024:
        raise ValueError('Navigation request too large')
    with tempfile.TemporaryDirectory(prefix='sheet-navigation-') as folder:
        request, result = Path(folder)/'request.nav', Path(folder)/'route.json'
        request.write_bytes(data)
        run = subprocess.run([java_executable(), '-Xmx512m', '-cp', str(jar),
            'io.github.luckolite.interpreter.NavigationBridge', str(request), str(result)],
            capture_output=True, text=True, encoding='utf-8', timeout=60)
        if run.returncode:
            raise ValueError('Navigation engine rejected decoded input: '+run.stderr.strip())
        return json.loads(result.read_text(encoding='utf-8'))


def _route(beats, directions):
    data = struct.pack('>ii', 0x4e415632, len(beats))
    data += b''.join(struct.pack('>d', value) for value in beats)
    return _bridge(data+encode(directions, [], len(beats)))


_REPEAT_WITNESS = ('unresolved-printed-repeat:', 'unresolved-printed-ending:')
_SECOND_ONLY = 'printed-second-pass-only:v1'
_BOTTOM_NOTE_SECOND_ONLY = 'printed-bottom-note-second-pass:v1'
_AUXILIARY_SECOND_ONLY = 'printed-auxiliary-second-pass:v1'
_AUXILIARY_OPENING = 'printed-auxiliary-opening:v1'


def _repeat_witnesses(page):
    return [event for event in page.get('score', {}).get('expressiveEvents', [])
            if event.get('kind') == 'UNRESOLVED_DIRECTION'
            and isinstance(event.get('eventId'), str)
            and event['eventId'].startswith(_REPEAT_WITNESS)]


def _requires_route(page):
    return bool(page.get('score', {}).get('playbackDirections') or _repeat_witnesses(page))


def _document_route(beats, directions, pages):
    data = bytearray(struct.pack('>ii', 0x4e415634, len(beats)))
    data += b''.join(struct.pack('>d', value) for value in beats)
    directions_wire = encode(directions, [], len(beats))
    data += directions_wire[:-4] + struct.pack('>i', len(pages))
    for i, page in enumerate(pages):
        count = len(page['measureBeats'])
        consecutive = i > 0 and source_pages_adjacent(pages[i-1], page)
        bound = consecutive and _bound_source_adjacent(pages[i-1], page)
        data += struct.pack('>ii??', count, page['score'].get('firstMeasureNumber', 0), consecutive, bound)
        data += encode([], _repeat_witnesses(page), count)[4:]
    return _bridge(data)


def _auxiliary_span(event, source, count):
    evidence = event.get('evidence', [])
    staff, staves = event.get('staffIndex', -1), event.get('staffCount', 0)
    first, last = event.get('start'), event.get('end')
    if (not event['eventId'].startswith(source + ':') or event['kind'] != 'UNRESOLVED_DIRECTION'
            or event['scope'] != 'VOICE' or not 0 <= staff < staves - 1
            or first is None or last is None or first['quarterBeatOffset'] != 0
            or last['quarterBeatOffset'] != 0 or not 0 <= first['measureIndex'] < last['measureIndex'] <= count
            or len(evidence) != 1 or evidence[0]['sourceId'] != source
            or evidence[0]['staffIndex'] != staff or evidence[0]['staffCount'] != staves):
        return False
    wording = ' '.join(evidence[0]['printedText'].lower().split())
    return wording == '' if source == _AUXILIARY_OPENING else wording in ('2nd time only', 'second time only')


def _auxiliary_staff_continuations(pages):
    result = None
    for index, (previous, following) in enumerate(zip(pages, pages[1:]), 1):
        prior, next_score = previous.get('score', {}), following.get('score', {})
        prior_count, next_count = len(previous['measureBeats']), len(following['measureBeats'])
        prior_first = prior.get('firstMeasureNumber', 0)
        if (prior_first <= 0 or prior_first + prior_count != next_score.get('firstMeasureNumber')
                or not _bound_source_adjacent(previous, following)):
            continue
        left = prior['clockContextSource']['origin']['physicalPageIndex']
        right = next_score['clockContextSource']['origin']['physicalPageIndex']
        outgoing = [e for e in prior.get('expressiveEvents', [])
                    if _auxiliary_span(e, _AUXILIARY_SECOND_ONLY, prior_count)
                    and e['end']['measureIndex'] == prior_count and e['evidence'][0]['pageIndex'] == left]
        incoming = [e for e in next_score.get('expressiveEvents', [])
                    if _auxiliary_span(e, _AUXILIARY_OPENING, next_count)
                    and e['start']['measureIndex'] == 0 and e['evidence'][0]['pageIndex'] == right]
        if len(outgoing) < 2 or len(incoming) != len(outgoing):
            continue
        outgoing.sort(key=lambda e: e['staffIndex'])
        incoming.sort(key=lambda e: e['staffIndex'])
        paired = list(zip(outgoing, incoming))
        if any(o['staffIndex'] != staff or i['staffIndex'] != staff
               or o['staffCount'] != len(paired) + 1 or i['staffCount'] != len(paired) + 1
               or o['start'] != outgoing[0]['start'] or i['end'] != incoming[0]['end']
               for staff, (o, i) in enumerate(paired)):
            continue
        carried = []
        for o, i in paired:
            if any(_auxiliary_span(e, _AUXILIARY_SECOND_ONLY, next_count)
                   and e['staffIndex'] == i['staffIndex'] and e['staffCount'] == i['staffCount']
                   and e['start']['measureIndex'] < i['end']['measureIndex']
                   for e in next_score.get('expressiveEvents', [])):
                continue
            word = dict(o['evidence'][0], pageIndex=right)
            mark = dict(i, eventId=f"{_AUXILIARY_SECOND_ONLY}:{right}:0:{i['staffIndex']}", evidence=[word])
            carried.append(mark)
        if not carried:
            continue
        score = dict(next_score, expressiveEvents=next_score.get('expressiveEvents', []) + carried)
        if result is None:
            result = list(pages)
        result[index] = dict(following, score=score)
    return pages if result is None else result

def _second_only_owners(page):
    score = page.get('score', {})
    roster = score.get('notes', [])
    muted = set()
    for mark in score.get('expressiveEvents', []):
        if _auxiliary_span(mark, _AUXILIARY_SECOND_ONLY, len(page['measureBeats'])):
            first, last = mark['start']['measureIndex'], mark['end']['measureIndex']
            muted.update(index for index, note in enumerate(roster)
                         if first <= note['measureIndex'] < last and note['staffIndex'] == mark['staffIndex']
                         and note['staffCount'] == mark['staffCount'])
            continue
        proof = mark.get('evidence', [])
        first, last = mark.get('start'), mark.get('end')
        bottom = mark.get('eventId', '').startswith(_BOTTOM_NOTE_SECOND_ONLY+':')
        source = _BOTTOM_NOTE_SECOND_ONLY if bottom else _SECOND_ONLY
        if (mark.get('kind') != 'UNRESOLVED_DIRECTION'
                or (bottom and mark.get('scope') != 'NOTE')
                or not mark.get('eventId', '').startswith(source+':')
                or len(proof) != 1 or proof[0].get('sourceId') != source
                or first is None or first.get('quarterBeatOffset') != 0
                or last != dict(measureIndex=first['measureIndex']+1, quarterBeatOffset=0)):
            continue
        positions = set((mark.get('targetEventId') or '').split(','))
        if len(positions) < 2:
            continue
        candidates = {}
        for i, note in enumerate(roster):
            if (note['measureIndex'] != first['measureIndex']
                    or note['staffIndex'] != mark['staffIndex']
                    or note['staffCount'] != mark['staffCount']):
                continue
            position = str(struct.unpack('>i', struct.pack('>f', note['positionInMeasure']))[0])
            if bottom:
                position += ':' + str(note['staffStep'])
            if position in positions:
                candidates[position] = i if position not in candidates else -1
        if len(candidates) != len(positions) or -1 in candidates.values():
            continue
        matches = [note for note in page['events'] if note.get('sourceNoteIndex') in candidates.values()]
        if len(matches) == len(positions) and len({note['sourceNoteIndex'] for note in matches}) == len(positions):
            muted.update(candidates.values())
    return muted



def _first_full_repeat_visits(occurrences):
    visits = {}
    for occurrence in occurrences:
        first, last = occurrence['start'], occurrence['end']
        measure = first['measureIndex']
        if first['quarterBeatOffset'] == 0 and last == dict(measureIndex=measure+1, quarterBeatOffset=0):
            visits.setdefault(measure, []).append(occurrence['occurrenceId'])
    return {bar: ids[0] for bar, ids in visits.items() if len(ids) == 2}


def source_pages_adjacent(previous, following):
    if 'sourcePage' not in previous and 'sourcePage' not in following:
        return True
    first, second = previous.get('sourcePage'), following.get('sourcePage')
    return type(first) is int and type(second) is int and first >= 0 and second == first + 1


def _bound_source_adjacent(previous, following):
    def origin(page):
        return page.get('score', {}).get('clockContextSource', {}).get('origin', {})

    def digest(value):
        return isinstance(value, str) and len(value) == 64 and all(ch in '0123456789abcdef' for ch in value)

    first, second = origin(previous), origin(following)
    page = first.get('physicalPageIndex')
    page_count = first.get('physicalPageCount')
    document = first.get('documentSha256')
    return (digest(document) and document == second.get('documentSha256')
            and digest(first.get('rasterSha256')) and digest(second.get('rasterSha256'))
            and type(first.get('width')) is int and first['width'] > 0
            and type(second.get('width')) is int and second['width'] > 0
            and type(page_count) is int and page_count == second.get('physicalPageCount')
            and type(page) is int and 0 <= page < page_count - 1
            and page + 1 == previous.get('sourcePage')
            and page + 1 == second.get('physicalPageIndex')
            and page + 2 == following.get('sourcePage'))


def require_retained_navigation(pages, occurrences):
    gaps = []
    boundary = 0
    for previous, following in zip(pages, pages[1:]):
        boundary += len(previous['measureBeats'])
        if not source_pages_adjacent(previous, following):
            gaps.append(boundary)
    if not gaps:
        return
    if occurrences is None:
        raise ValueError('Navigation across a partial score requires source occurrences')
    for previous, following in zip(occurrences, occurrences[1:]):
        departure, destination = previous['end'], following['start']
        gap = bisect_right(gaps, destination['measureIndex'])
        if (gap < len(gaps)
                and (gaps[gap], 0) < (departure['measureIndex'], departure['quarterBeatOffset'])):
            raise ValueError('A navigation return crosses omitted score pages; include those pages to export playback')


def arrangement_ranges(pages):
    data = struct.pack('>ii', 0x4e415031, len(pages))
    for page in pages:
        data += struct.pack('>ii', len(page['measureBeats']),
                            page.get('score', {}).get('firstMeasureNumber', 0))
    return _bridge(data)


def tempo_restart_pages(pages):
    """Return starts that can need a reset from a previous arrangement's tempo."""
    if (not any(page.get('score', {}).get('firstMeasureNumber', 0) > 0 for page in pages[1:])
            or not any(page.get('score', {}).get('tempoChanges') for page in pages)):
        return set()
    return {part['firstPage'] for part in arrangement_ranges(pages)[1:]}


_REPEAT_CAPTION = 'unresolved-repeat-caption:'
_REPEAT_CAPTION_SOURCE = 'printed-repeat-caption:v1:'


def _caption_navigation(pages):
    def witnesses(page):
        return [event for event in page.get('score', {}).get('expressiveEvents', [])
                if event['kind'] == 'UNRESOLVED_DIRECTION'
                and event['eventId'].startswith(_REPEAT_CAPTION)]

    if len(pages) < 2 or not any(witnesses(page) for page in pages):
        return pages
    data = bytearray(struct.pack('>ii', 0x4e415234, len(pages)))
    for index, page in enumerate(pages):
        score = page.get('score', {})
        durations = page['measureBeats']
        count = len(durations)
        consecutive = index > 0 and source_pages_adjacent(pages[index-1], page)
        data.extend(struct.pack('>ii?', count, score.get('firstMeasureNumber', 0), consecutive))
        bars = [[] for _ in durations]
        for note in score.get('notes', []):
            measure = note['measureIndex']
            clef = note.get('clefBottomDiatonic', -1)
            if 0 <= measure < count and clef != -1 and note.get('kind', 'PITCHED') == 'PITCHED':
                bars[measure].append(clef + note['staffStep'])
        for beats, pitches in zip(durations, bars):
            data.extend(struct.pack('>di', beats, len(pitches)))
            for pitch in pitches:
                data.extend(struct.pack('>i', pitch))
        data.extend(encode(score.get('playbackDirections', []),
                           _repeat_witnesses(page) + witnesses(page), count))
    recovered = _bridge(data)
    if not recovered:
        return pages
    result = list(pages)
    for index, directions in recovered.items():
        index = int(index)
        page = pages[index]
        score = page['score']
        resolved = {item['sourceId'][len(_REPEAT_CAPTION_SOURCE):]
                    for direction in directions for item in direction['details']['evidence']
                    if item['sourceId'].startswith(_REPEAT_CAPTION_SOURCE)}
        result[index] = dict(page, score=dict(
            score, playbackDirections=score.get('playbackDirections', []) + directions,
            expressiveEvents=[item for item in score.get('expressiveEvents', [])
                              if item['eventId'] not in resolved]))
    return result


def _same_document_segno(pages):
    def score(page):
        return page.get('score', {})

    kinds = [direction['kind'] for page in pages
             for direction in score(page).get('playbackDirections', [])]
    if len(pages) < 2 or kinds.count(2) < 2 or 0 not in kinds:
        return pages

    data = bytearray(struct.pack('>ii', 0x4e415235, len(pages)))
    for page in pages:
        source = score(page)
        origin = source.get('clockContextSource', {}).get('origin', {})
        digest = origin.get('documentSha256', '') if origin.get('rasterSha256') and origin.get('width', 0) > 0 else ''
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
            digest = ''
        digest = digest.encode('ascii')
        durations = page['measureBeats']
        data.extend(struct.pack('>iiiiH', len(durations), source.get('firstMeasureNumber', 0),
                                origin.get('physicalPageIndex', -1), origin.get('physicalPageCount', 0),
                                len(digest)))
        data.extend(digest)
        bars = [{} for _ in durations]
        for note in sorted(source.get('notes', []), key=lambda n: n['positionInMeasure']):
            measure = note['measureIndex']
            staff = note['staffIndex']
            clef = note.get('clefBottomDiatonic', -1)
            if (0 <= measure < len(bars) and 0 <= staff < 64 and clef != -1
                    and note.get('kind', 'PITCHED') == 'PITCHED'):
                bars[measure].setdefault(staff, []).append((
                    clef + note['staffStep'], note.get('beamCount', 0), note.get('augmentationDots', 0)))
        for beats, bar in zip(durations, bars):
            staves = max(bar, default=-1) + 1
            data.extend(struct.pack('>di', beats, staves))
            for staff in range(staves):
                attacks = bar.get(staff, ())
                data.extend(struct.pack('>i', len(attacks)))
                for attack in attacks:
                    data.extend(struct.pack('>iii', *attack))
        data.extend(encode(source.get('playbackDirections', []), [], len(durations)))

    recovered = _bridge(data)
    if not recovered:
        return pages
    result = list(pages)
    for index, mark in recovered.items():
        index = int(index)
        page = pages[index]
        result[index] = dict(page, score=dict(
            score(page), playbackDirections=score(page).get('playbackDirections', []) + [mark]))
    return result


def project_navigation(document, bpm=120):
    pages = _auxiliary_staff_continuations(_same_document_segno(_caption_navigation(document['pages'])))
    if pages is not document['pages']:
        document = dict(document, pages=pages)
    for page in pages:
        for note in page['events']:
            validate_unpitched(note)
    if not any(_requires_route(page) for page in pages):
        restarts = tempo_restart_pages(pages)
        if not restarts:
            return document
        return dict(document, pages=[dict(page, _resetInitialTempo=True) if i in restarts else page
                                     for i, page in enumerate(pages)])
    if len(pages)<=1:
        return _project_one(document,bpm)
    ranges=arrangement_ranges(pages)
    output,diagnostics,occurrences=[],[],[]
    for index, part in enumerate(ranges):
        first,last=part['firstPage'],part['pageAfterLast']
        played=_project_one(dict(document,pages=pages[first:last]),bpm,first,reset_tempo=index > 0)
        output.extend(played['pages']);diagnostics.extend(played.get('navigationDiagnostics',[]))
        occurrences.extend(dict(occurrence,arrangementIndex=index,sourceFirstPage=first)
                           for occurrence in played.get('navigationOccurrences',[]))
    return dict(document,pages=output,navigationDiagnostics=diagnostics,navigationOccurrences=occurrences)


def _project_one(document, bpm=120, source_page_offset=0, *, reset_tempo=False):
    pages = document['pages']
    if not any(_requires_route(page) for page in pages):
        pages = mark_linear_slide_visits(pages, f'linear:{source_page_offset}')
        if reset_tempo and pages:
            pages[0] = dict(pages[0], _resetInitialTempo=True)
        return dict(document, pages=pages)
    from .source_tempo_clock import onset as tempo_onset
    beats, directions, notes, tempos = [], [], [], []
    offset = 0.0
    for page_index, page in enumerate(pages):
        local = list(page['measureBeats']); starts = [0.0]
        for value in local:
            if not isinstance(value, (int, float)) or not math.isfinite(value) or not .125 <= value <= 128:
                raise ValueError('Invalid decoded measure duration')
            starts.append(starts[-1]+value)
        if not math.isclose(starts[-1], page['totalBeats'], abs_tol=1e-6):
            raise ValueError('Decoded page extent disagrees with its measures')
        for direction in page.get('score', {}).get('playbackDirections', []):
            row = copy.deepcopy(direction); row['measureBoundary'] += len(beats)
            if row.get('details', {}).get('end') is not None:
                row['details']['end']['measureIndex'] += len(beats)
            directions.append(row)
        second_only = _second_only_owners(page)
        for index, note in enumerate(page['events']):
            validate_unpitched(note)
            physical_page=page.get('sourcePage',page_index+source_page_offset)
            row = dict(note, startBeat=offset+note['startBeat'], sourceEventId=f'note:{physical_page}:{index}')
            if (not math.isfinite(row['startBeat']) or not math.isfinite(row['durationBeats'])
                    or row['durationBeats'] <= 0 or row['startBeat'] < offset
                    or row['startBeat'] >= offset+starts[-1]):
                raise ValueError('Invalid decoded note span')
            if note.get('sourceNoteIndex') in second_only:
                row['_secondOnlyBar'] = len(beats)+note['measureIndex']
            notes.append(row)
        for change in page.get('score', {}).get('tempoChanges', []):
            m = change['measureIndex']; position = change['positionInMeasure']
            if not 0 <= m < len(local) or not math.isfinite(position) or not 0 <= position <= 1:
                raise ValueError('Invalid decoded tempo anchor')
            if not math.isfinite(change['bpm']) or not 15 <= change['bpm'] <= 1600:
                raise ValueError('Invalid decoded tempo')
            written = tempo_onset(page, change)
            tempos.append((offset+starts[m]+(local[m]*position if written is None else written),
                           change['bpm']))
        beats.extend(local); offset += starts[-1]
    starts = [0.0]
    for value in beats: starts.append(starts[-1]+value)
    # Traversal occurrences cover at most one source bar. Index overlapping note
    # spans once instead of scanning an entire document for every repeated bar.
    note_buckets=[[] for _ in beats]
    entries=0
    for note in notes:
        first=max(0,bisect_right(starts,note['startBeat'])-1)
        last=min(len(beats),bisect_left(starts,note['startBeat']+note['durationBeats']))
        entries+=max(0,last-first)
        if entries>1000000:
            raise ValueError('Decoded sustain spans exceed bounded export capacity')
        for measure in range(first,last):note_buckets[measure].append(note)
    if any(_repeat_witnesses(page) for page in pages):
        document_route = _document_route(beats, directions, pages)
        if not document_route['recoveredDirections'] and not directions:
            pages = mark_linear_slide_visits(pages, f'linear:{source_page_offset}')
            if reset_tempo and pages:
                pages[0] = dict(pages[0], _resetInitialTempo=True)
            return dict(document, pages=pages)
        route = document_route['route']
    else:
        route = _route(beats, directions)
    if not route['complete']:
        raise ValueError('Navigation traversal is incomplete: '+str(route['diagnostics']))
    if route['diagnostics']:
        raise ValueError('Navigation performance is unresolved: '+str(route['diagnostics']))
    require_retained_navigation(pages, route['occurrences'])
    tempos.sort(key=lambda item: item[0])
    output_notes, output_tempos, spans = [], [], []
    prior_end = None
    run_predecessors = {}
    unpitched_visits = {}
    run_visit = None
    slide_run_visit = None
    first_visits = _first_full_repeat_visits(route['occurrences'])
    for occurrence in route['occurrences']:
        first, last = occurrence['start'], occurrence['end']
        source_start = starts[first['measureIndex']]+first['quarterBeatOffset']
        source_end = starts[last['measureIndex']]+last['quarterBeatOffset']
        performed = occurrence['performanceStartBeat']; span = source_end-source_start
        index = len(spans); spans.append(span)
        if prior_end is None or prior_end != source_start:
            slide_run_visit = occurrence['occurrenceId']
        contiguous = prior_end is not None and math.isclose(prior_end, source_start, abs_tol=1e-8)
        if not contiguous:
            run_predecessors.clear()
            unpitched_visits.clear()
            run_visit = occurrence["occurrenceId"]
        for note in sorted(note_buckets[first['measureIndex']], key=lambda row: row['startBeat']):
            if occurrence['occurrenceId'] == first_visits.get(note.get('_secondOnlyBar')):
                continue
            onset, finish = note['startBeat'], note['startBeat']+note['durationBeats']
            a, b = max(onset, source_start), min(finish, source_end)
            if b <= a: continue
            if event_kind(note) == 'UNPITCHED':
                owner = note['sourceEventId'] + '@' + str(run_visit)
                previous_end = unpitched_visits.get(owner)
                continuation = contiguous and previous_end is not None and math.isclose(previous_end, a, abs_tol=1e-8)
                output_notes.append(dict(note, measureIndex=index, startBeat=performed+a-source_start,
                    durationBeats=b-a, tiedFromPrevious=False, boundaryTies=0,
                    occurrenceId=occurrence['occurrenceId'], _unpitchedAttackId=owner,
                    _unpitchedContinuation=continuation, clippedEntry=onset < a))
                unpitched_visits[owner] = b
                continue
            tied = bool(note['tiedFromPrevious'])
            if onset < source_start: tied = contiguous
            elif tied:
                # A jump can land just before a jittered tied onset. Its old
                # predecessor may have been skipped, even when a same-pitch
                # performed note happens to end nearby. Only source notes
                # already visited in this contiguous run can own that tie.
                lanes = [(note['staffCount'], note['staffIndex'], note['midi'])]
                if note['staffIndex'] == 0 and note['staffCount'] in (1, 2):
                    lanes.append((3-note['staffCount'], 0, note['midi']))
                tied = any(run_predecessors.get(lane, float('inf')) < onset for lane in lanes)
            output_notes.append(dict(note, measureIndex=index, startBeat=performed+a-source_start,
                durationBeats=b-a, tiedFromPrevious=tied, boundaryTies=0,
                occurrenceId=occurrence['occurrenceId']))
            if note.get('_slideOrigins'):
                output_notes[-1]['_slideContext'] = dict(
                    visitId=f'navigation:{source_page_offset}:{slide_run_visit}',
                    attack=onset == a and not note.get('tiedFromPrevious', False),
                    clippedEntry=onset < a, clippedRelease=b < finish)
            lane = (note['staffCount'], note['staffIndex'], note['midi'])
            run_predecessors[lane] = min(onset, run_predecessors.get(lane, onset))
        current = bpm
        for at, value in tempos:
            if at <= source_start: current = value
            else: break
        if not contiguous or not output_tempos:
            output_tempos.append(dict(measureIndex=index, positionInMeasure=0, bpm=current))
        for at, value in tempos:
            if source_start < at < source_end or contiguous and at == source_start:
                output_tempos.append(dict(measureIndex=index, positionInMeasure=(at-source_start)/span, bpm=value))
        prior_end = source_end
    total = route['occurrences'][-1]['performanceEndBeat'] if spans else 0
    return dict(document, navigationDiagnostics=route['diagnostics'], navigationOccurrences=route['occurrences'],
        pages=[dict(events=output_notes, measureBeats=spans, totalBeats=total,
                    score=dict(tempoChanges=output_tempos))])
