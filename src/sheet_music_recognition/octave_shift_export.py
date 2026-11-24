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


def retained(page, continuations):
    """Read optically owned staff spans, optionally bounded by written attack onsets."""
    spans = page.get('printedOctaveSpans', [])
    if not isinstance(spans, list):
        raise ValueError('Printed octave spans must be a list')
    covered = set()
    fragments = defaultdict(list)
    for row in spans:
        keys = ('firstMeasureIndex', 'lastMeasureIndex', 'staffIndex', 'staffCount', 'octaveShift')
        interval = ('firstOnsetTicks', 'followingOnsetTicks')
        if (not isinstance(row, dict) or set(row) not in (set(keys), set(keys + interval))
                or any(type(value) is not int for value in row.values())):
            raise ValueError('Invalid printed octave span fields')
        first, last, staff, count, shift = (row[key] for key in keys)
        bounds = tuple(row[key] for key in interval) if interval[0] in row else None
        if (not 0 <= staff < count or not 0 <= first <= last < len(page['measureBeats'])
                or not 1 <= abs(shift) <= 2 or first in continuations or last + 1 in continuations):
            raise ValueError('Invalid printed octave span bounds')
        if bounds and (first != last or not 0 <= bounds[0] < bounds[1] < round(page['measureBeats'][first] * 10080)):
            raise ValueError('Invalid partial octave span onset bounds')
        if bounds:
            identity = first, staff, count
            if identity in covered or any(
                    bounds[0] < stop and start < bounds[1] for start, stop in fragments[identity]):
                raise ValueError('Printed staff octave spans overlap')
            fragments[identity].append(bounds)
        else:
            positions = {(measure, staff, count) for measure in range(first, last + 1)}
            if positions & covered or positions & fragments.keys():
                raise ValueError('Printed staff octave spans overlap')
            covered.update(positions)
        yield first, last, staff, count, shift, bounds


def _staff_span(measures, shift, owned, covered):
    """Find the first written attack and last release across all exported voices."""
    endpoints = []
    for index, measure in enumerate(measures):
        cursor = onset = 0
        for item in measure:
            if item.tag in ('backup', 'forward'):
                value = int(item.findtext('duration'))
                cursor += value if item.tag == 'forward' else -value
            if item.tag != 'note':
                continue
            duration = int(item.findtext('duration', '0'))
            if item.find('chord') is None:
                onset = cursor
                cursor += duration
            if item.find('rest') is not None:
                continue
            if item in covered or owned.get(item) != shift or item.find('pitch') is None:
                raise ValueError('A complete printed octave span conflicts with a written pitch')
            covered.add(item)
            endpoints.append((index, onset, onset + duration, item))
    if not endpoints or endpoints[0][0] != 0 or endpoints[-1][0] != len(measures) - 1:
        raise ValueError('The endpoints of a printed staff octave span require written owners')
    first = min(endpoints, key=lambda row: (row[0], row[1]))[-1]
    last = max(reversed(endpoints), key=lambda row: (row[0], row[2]))[-1]
    if first.findtext('staff') != last.findtext('staff'):
        raise ValueError('A printed octave span cannot cross exported staves')
    return first, last


def _partial_staff_octave(bar, shift, bounds, owned, covered):
    import xml.etree.ElementTree as ET

    start, stop = bounds
    cursor = onset = 0
    first = boundary = None
    accepted = []
    for node in bar:
        if node.tag in ('backup', 'forward'):
            length = int(node.findtext('duration'))
            cursor += length if node.tag == 'forward' else -length
        if node.tag != 'note':
            continue
        if node.find('chord') is None:
            onset = cursor
            cursor += int(node.findtext('duration', '0'))
        if node.find('rest') is not None:
            continue
        current = owned.get(node, 0)
        if onset == stop and node.find('pitch') is not None and current != shift:
            boundary = node
        if not start <= onset < stop:
            continue
        if node in covered or current != shift or node.find('pitch') is None:
            raise ValueError('A partial staff octave span conflicts with a written attack')
        if onset == start:
            first = node
        accepted.append(node)
    if first is None or boundary is None or len({n.findtext('staff') for n in accepted + [boundary]}) != 1:
        raise ValueError('A partial staff octave span requires exact endpoints on one staff')
    covered.update(accepted)

    before = next(node for node in bar if node.tag in ('note', 'backup', 'forward'))
    result = []
    for position, kind in ((start, 'down' if shift > 0 else 'up'), (stop, 'stop')):
        direction = ET.Element('direction', placement='above' if shift > 0 else 'below')
        subtype = ET.SubElement(direction, 'direction-type')
        ET.SubElement(subtype, 'octave-shift', type=kind, size=str(7 * abs(shift) + 1), number='16')
        if position:
            ET.SubElement(direction, 'offset').text = str(position)
        staff = first.findtext('staff')
        if staff is not None:
            ET.SubElement(direction, 'staff').text = staff
        result.append((bar, before, direction))
    return result


def apply(root, owned, measure_pages=None, printed_staff_spans=None):
    import xml.etree.ElementTree as ET
    source_page = (measure_pages or {}).get
    for part in root.findall('part'):
        notes = list(part.findall('.//note'))
        parents = {child: parent for parent in part.iter() for child in parent}
        measure_order = {bar: index for index, bar in enumerate(part.findall('measure'))}
        staff_owned = set()
        partial_owned = set()
        explicit, fragments = [], []
        for row in (printed_staff_spans or {}).get(part, ()):
            measures, shift, *optional = row
            bounds = optional[0] if optional else None
            if bounds is None:
                first, final = _staff_span(measures, shift, owned, staff_owned)
                explicit.append((first, final, shift, measures[0]))
            else:
                if len(measures) != 1:
                    raise ValueError('A partial staff octave span must have one measure')
                fragment = _partial_staff_octave(measures[0], shift, bounds, owned, partial_owned)
                fragments.extend(fragment)
        entry_measures = {measure: first for first, _, _, measure in explicit}
        last = {n.findtext('voice', '1'): n for n in notes}
        active, used = {}, set()
        numbers = {}
        for n in notes:
            if n not in staff_owned and n not in partial_owned and owned.get(n, 0):
                numbers.setdefault(n.findtext('voice', '1'), len(numbers) + 1)
        if fragments and len(numbers) == 16:
            partial_owned.clear()
            fragments.clear()
            numbers.clear()
            for n in notes:
                if n not in staff_owned and owned.get(n, 0):
                    numbers.setdefault(n.findtext('voice', '1'), len(numbers) + 1)
        if len(numbers) > 16:
            raise ValueError('Too many octave direction voices')
        def direction(note, entry, stop, after=False, staff_wide=False):
            shift, staff, number, _ = entry
            d = ET.Element('direction', placement='above' if shift > 0 else 'below')
            t = ET.SubElement(d, 'direction-type')
            ET.SubElement(t, 'octave-shift', type='stop' if stop else 'down' if shift > 0 else 'up', size=str(7*abs(shift)+1), number=str(number))
            if not staff_wide:
                ET.SubElement(d, 'voice').text = note.findtext('voice', '1')
            if staff is not None:
                ET.SubElement(d, 'staff').text = staff
            parent = parents[note]
            parent.insert(list(parent).index(note) + int(after), d)
        for note in notes:
            measure = parents[note]
            if measure in entry_measures:
                anchor = entry_measures.pop(measure)
                for entry in active.values():
                    direction(anchor, entry, True)
                active.clear()
                used.clear()
            if note in staff_owned:
                continue
            voice, staff = note.findtext('voice', '1'), note.findtext('staff')
            shift = 0 if note in partial_owned else owned.get(note, 0)
            if type(shift) is not int or not -2 <= shift <= 2:
                raise ValueError('Invalid retained octave shift')
            old = active.get(voice)
            if old is not None:
                previous = old[3]
                previous_page = source_page(parents[previous])
                current_page = source_page(parents[note])
                page_turn = (previous_page is not None and current_page is not None
                             and current_page > previous_page)
                # Voice numbers can be reused across pages with no occurrence
                # of that voice between them. A local octave cannot span the gap.
                skipped_page = page_turn and current_page > previous_page + 1
                if skipped_page or old[0] != shift or old[1] != staff:
                    direction(previous if page_turn else note, old, True, after=page_turn)
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
        def fragment_order(item):
            bar, _, element = item
            kind = element.find('direction-type/octave-shift').get('type')
            return measure_order[bar], int(element.findtext('offset', '0')), kind != 'stop'

        for bar, before, element in sorted(fragments, key=fragment_order):
            bar.insert(list(bar).index(before), element)
        for first, final, shift, _ in explicit:
            entry = shift, first.findtext('staff'), 1, None
            direction(first, entry, False, staff_wide=True)
            direction(final, entry, True, after=True, staff_wide=True)
