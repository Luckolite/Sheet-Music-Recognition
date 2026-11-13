# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Export admitted wedges using owned written clocks and explicit bar boundaries."""
from .technique_export import position


def _printed_alternatives(score):
    source = 'printed-alternative-hairpin'
    groups = {}
    for event in score.get('expressiveEvents', []):
        kind = event.get('kind')
        if (kind not in ('CRESCENDO', 'DIMINUENDO') or event.get('scope') != 'UNRESOLVED'
                or event.get('staffIndex') != 0 or event.get('staffCount') != 1
                or any(event.get(key) is not None for key in ('start', 'end', 'targetEventId'))
                or not any(e.get('sourceId') == source for e in event.get('evidence', []))):
            continue
        text = event.get('qualifierText')
        if not isinstance(text, str):
            continue
        try:
            measure, first, last, row, depth = text.split(':')
            measure, row = int(measure), int(row)
            first, last, depth = position(float(first)), position(float(last)), float(depth)
        except (ValueError, OverflowError):
            continue
        if (measure < 0 or row not in (0, 1) or first is None or last is None
                or first >= last or not .8 <= depth <= 7):
            continue
        groups.setdefault((measure, first, last), []).append((row, kind, depth))
    for key, group in groups.items():
        if len(group) != 2:
            continue
        upper, lower = sorted(group)
        if upper[0] == 0 and lower[0] == 1 and upper[1] != lower[1] and 1.5 <= lower[2] - upper[2] <= 3:
            yield key, (upper, lower)


def plan(page, rendered, bars, page_identity):
    notes = page.get('score', {}).get('notes', [])
    events = page.get('events', [])
    if len(notes) != len(events) or len(rendered) != len(notes):
        return []

    def scoped(note, measure, staff, count, shared):
        return (note.get('measureIndex') == measure
                and (note.get('staffCount') == count if shared else note.get('staffIndex') == staff))

    def clock(i, measure, count):
        note, event, row = notes[i], events[i], rendered[i]
        if (any(type(note.get(k)) is not int for k in ('measureIndex', 'staffIndex', 'staffCount'))
                or note['staffCount'] != count or not 0 <= note['staffIndex'] < count
                or type(event.get('sourceNoteIndex')) is not int or event['sourceNoteIndex'] != i
                or event.get('durationFallback') is not False
                or any(type(event.get(k)) is not int or event[k] != note[k]
                       for k in ('measureIndex', 'staffIndex', 'staffCount'))
                or row.get('grace', False)
                or type(row.get('start')) is not int or type(row.get('end')) is not int
                or not bars[measure]['start'] <= row['start'] < bars[measure]['start'] + bars[measure]['length']
                or row['end'] <= row['start']):
            return None
        return row['start'], row['end']

    def column(measure, p, staff, count, shared):
        owners = [i for i, n in enumerate(notes) if scoped(n, measure, staff, count, shared)
                  and position(n.get('positionInMeasure')) == p]
        if not owners:
            return 'unowned', None
        clocks = [clock(i, measure, count) for i in owners]
        if any(c is None for c in clocks) or len({c[0] for c in clocks}) != 1:
            return 'invalid', None
        return 'exact', clocks[0][0]

    def whole_hold(measure, p, staff, count, shared):
        owners = [i for i, n in enumerate(notes) if scoped(n, measure, staff, count, shared)
                  and n.get('staffCount') == count]
        if not owners:
            return None
        for i in owners:
            n = notes[i]
            x = position(n.get('positionInMeasure'))
            c = clock(i, measure, count)
            if (x is None or x <= p or n.get('leadingRestBeats', 0) != 0
                    or n.get('followingRestBeats', 0) != 0 or c != (
                        bars[measure]['start'], bars[measure]['start'] + bars[measure]['length'])):
                return None
        return bars[measure]['start']

    result, seen = [], set()
    for index, change in enumerate(page.get('score', {}).get('dynamicChanges', [])):
        m, end, staff, count = (change.get(k) for k in
                               ('measureIndex', 'endMeasureIndex', 'staffIndex', 'staffCount'))
        start_pos, end_pos = position(change.get('positionInMeasure')), position(change.get('endPosition'))
        direction = change.get('direction')
        shared = change.get('sharedStaffs', False)
        timing = change.get('sharedTiming', shared)
        if (type(direction) is not int or direction not in (-1, 1)
                or type(m) is not int or type(end) is not int or not 0 <= m <= end < len(bars)
                or type(staff) is not int or type(count) is not int or not 0 <= staff < count <= 8
                or start_pos is None or end_pos is None
                or type(shared) is not bool or type(timing) is not bool):
            continue
        timing |= shared
        status, start = column(m, start_pos, staff, count, timing)
        if status == 'invalid':
            continue
        if status == 'unowned':
            start = whole_hold(m, start_pos, staff, count, timing)
            if start is None:
                start = bars[m]['start'] + int(bars[m]['length'] * start_pos + .5)
        if end_pos in (0, 1):
            stop = bars[end]['start'] + int(bars[end]['length'] * end_pos)
        else:
            status, stop = column(end, end_pos, staff, count, timing)
            if status == 'invalid':
                continue
            if status == 'unowned':
                stop = bars[end]['start'] + int(bars[end]['length'] * end_pos + .5)
        if stop <= start:
            continue
        targets = sorted({n['staffIndex'] for n in notes
                          if type(n.get('staffIndex')) is int and 0 <= n['staffIndex'] < count
                          and type(n.get('staffCount')) is int and n['staffCount'] == count}) if shared else [staff]
        for target in targets:
            key = (m, end, target, count, direction, start, stop)
            if key in seen:
                continue
            seen.add(key)
            result.append(dict(identity=f'{page_identity}/hairpin:{index}:{target}',
                kind='CRESCENDO' if direction == 1 else 'DIMINUENDO', scope='PART',
                staffIndex=target, staffCount=count, startTick=start, endTick=stop,
                ownerIds=[], evidence=[], _hairpin='crescendo' if direction == 1 else 'diminuendo',
                _hairpinStartBarStart=bars[m]['start'], _hairpinEndBarStart=bars[end]['start']))
    for (measure, first, last), pair in _printed_alternatives(page.get('score', {})):
        if measure >= len(bars):
            continue
        start_status, start = column(measure, first, 0, 1, False)
        end_status, stop = column(measure, last, 0, 1, False)
        if start_status != 'exact' or end_status != 'exact' or stop <= start:
            continue
        bar_start = bars[measure]['start']
        for row, kind, depth in pair:
            result.append(dict(identity=f'{page_identity}/alternative-hairpin:{measure}:{row}',
                kind=kind, scope='PART', staffIndex=0, staffCount=1, startTick=start, endTick=stop,
                ownerIds=[], evidence=[], _hairpin='crescendo' if kind == 'CRESCENDO' else 'diminuendo',
                _hairpinStartBarStart=bar_start, _hairpinEndBarStart=bar_start,
                _hairpinGeometry={'default-y': str(round(-10 * (4 + depth), 3))}))
    return result
