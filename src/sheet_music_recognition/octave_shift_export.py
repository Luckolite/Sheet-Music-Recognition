# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Staff-owned octave directions; pitches remain the supplied sounding pitches."""
from collections import defaultdict


def _lane(notes):
    edges = defaultdict(lambda: [0] * 5)
    for n in notes:
        shift = 0 if n.get('kind', 'PITCHED') == 'UNPITCHED' else n.get('octaveShift', 0)
        if type(shift) is not int or not -2 <= shift <= 2:
            raise ValueError('Invalid retained octave shift')
        if n.get('grace', False):
            continue
        edges[n['start']][shift + 2] += 1
        edges[n['end']][shift + 2] -= 1
    active = [0] * 5
    previous = None
    spans = []
    for at in sorted(edges):
        if previous is not None and at > previous:
            shifts = [i - 2 for i, count in enumerate(active) if count]
            if len(shifts) > 1:
                raise ValueError('Conflicting simultaneous staff octave shifts')
            if shifts and shifts[0]:
                shift = shifts[0]
                if spans and spans[-1][1] == previous and spans[-1][2] == shift:
                    spans[-1] = spans[-1][0], at, shift
                else:
                    spans.append((previous, at, shift))
        active = [x + y for x, y in zip(active, edges[at])]
        previous = at
    for n in notes:
        if n.get('grace', False):
            shift = 0 if n.get('kind', 'PITCHED') == 'UNPITCHED' else n.get('octaveShift', 0)
            found = {s for a, b, s in spans if a <= n['start'] <= b}
            if (not shift and found) or (shift and found != {shift}):
                raise ValueError('Grace octave direction lacks an unambiguous principal span')
    return spans


def plan(notes):
    spans = [(a, b, shift, voice) for voice in sorted({n['voice'] for n in notes})
             for a, b, shift in _lane([n for n in notes if n['voice'] == voice])]
    active = []
    result = []
    for a, b, shift, voice in sorted(spans):
        active = [(end, number) for end, number in active if end > a]
        number = next((v for v in range(1, 17) if v not in {n for _, n in active}), None)
        if number is None:
            raise ValueError('Too many overlapping octave directions')
        active.append((b, number))
        result.append((a, b, shift, voice, number))
    return result


def apply(root, owned, measure_pages=None):
    import xml.etree.ElementTree as ET
    source_page = (measure_pages or {}).get
    for part in root.findall('part'):
        notes = list(part.findall('.//note'))
        parents = {child: parent for parent in part.iter() for child in parent}
        measure_order = {bar: index for index, bar in enumerate(part.findall('measure'))}
        last = {n.findtext('voice', '1'): n for n in notes}
        active, used = {}, set()
        numbers = {}
        for n in notes:
            if owned.get(n, 0):
                numbers.setdefault(n.findtext('voice', '1'), len(numbers) + 1)
        if len(numbers) > 16:
            raise ValueError('Too many octave direction voices')
        def direction(note, entry, stop, after=False):
            shift, staff, number, _ = entry
            d = ET.Element('direction', placement='above' if shift > 0 else 'below')
            t = ET.SubElement(d, 'direction-type')
            ET.SubElement(t, 'octave-shift', type='stop' if stop else 'down' if shift > 0 else 'up', size=str(7*abs(shift)+1), number=str(number))
            ET.SubElement(d, 'voice').text = note.findtext('voice', '1')
            if staff is not None:
                ET.SubElement(d, 'staff').text = staff
            parent = parents[note]
            parent.insert(list(parent).index(note) + int(after), d)
        for note in notes:
            voice, staff = note.findtext('voice', '1'), note.findtext('staff')
            shift = owned.get(note, 0)
            if type(shift) is not int or not -2 <= shift <= 2:
                raise ValueError('Invalid retained octave shift')
            old = active.get(voice)
            if old is not None and (old[0] != shift or old[1] != staff):
                previous = old[3]
                prior_bar, next_bar = parents[previous], parents[note]
                previous_page = source_page(prior_bar)
                current_page = source_page(next_bar)
                # A reused voice can omit measures across a page turn. Its stop
                # belongs to the last owned note, before the unoccupied measures.
                disconnected = (
                    previous_page is not None and current_page is not None
                    and current_page > previous_page
                    and (current_page > previous_page + 1
                         or measure_order[next_bar] > measure_order[prior_bar] + 1)
                )
                direction(previous if disconnected else note, old, True, after=disconnected)
                used.remove(old[2])
                del active[voice]
            if shift:
                continuing = voice in active
                number = numbers[voice]
                active[voice] = shift, staff, number, note
                if not continuing:
                    direction(note, active[voice], False)
                    used.add(number)
            if last[voice] is note and voice in active:
                old = active.pop(voice)
                direction(note, old, True, after=True); used.remove(old[2])
