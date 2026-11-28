# Copyright 2026 Luckolite
# SPDX-License-Identifier: Apache-2.0
"""Project retained printed beams onto the MusicXML notes that own their stems."""

from collections import defaultdict
from dataclasses import dataclass
from xml.etree.ElementTree import Element

_BEAM_LEVELS = {
    name: index for index, name in enumerate(
        ('eighth', '16th', '32nd', '64th', '128th', '256th', '512th', '1024th'), start=1)
}


def bind(source, events):
    """Map physical source columns to unambiguous events in the current page."""
    if not source['groups']:
        return []
    origins = defaultdict(list)
    for index, origin in enumerate(source['noteOrigins']):
        if origin is not None:
            origins[origin['pageIndex'], origin['noteIndex']].append(index)
    emitted = defaultdict(list)
    for index, event in enumerate(events):
        owner = event.get('sourceNoteIndex')
        if type(owner) is int:
            emitted[owner].append(index)
    groups = []
    for group in source['groups']:
        columns = []
        for column in group['columns']:
            identities = []
            for anchor in column['members']:
                owners = origins[anchor['pageIndex'], anchor['noteIndex']]
                if len(owners) != 1 or len(emitted[owners[0]]) != 1:
                    break
                identities.append(emitted[owners[0]][0])
            else:
                columns.append(tuple(identities))
                continue
            columns.append(())
        groups.append((columns, group['bridges'], group['hooks']))
    return groups


@dataclass(frozen=True)
class WrittenColumn:
    node: Element
    part: str
    bar: int
    voice: int
    start: int
    end: int
    grace_position: tuple[int, bool, int] | None = None

    @property
    def levels(self):
        return _BEAM_LEVELS.get(self.node.findtext('type'), 0)

    def precedes(self, other):
        if self.end != other.start:
            return False
        if self.grace_position is None:
            return other.grace_position is None
        principal, after, position = self.grace_position
        return other.grace_position == (principal, after, position + 1)


def write(groups, rendered_columns):
    for owners, bridges, hooks in groups:
        columns = [rendered_columns.get(frozenset(heads)) for heads in owners]
        joins = []
        for mask, left, right in zip(bridges, columns, columns[1:]):
            if (left is None or right is None or left.part != right.part
                    or left.bar != right.bar or left.voice != right.voice
                    or not left.precedes(right)):
                joins.append(0)
                continue
            joins.append(mask & ((1 << min(left.levels, right.levels)) - 1))
        edges = [0, *joins, 0]
        hook_levels = {(h['columnIndex'], h['level']): h['direction'].lower() + ' hook' for h in hooks}
        for index, column in enumerate(columns):
            if column is None:
                continue
            before, after = edges[index:index + 2]
            if not (before | after) & 1:
                continue
            node = column.node
            # MusicXML places beams after notehead/staff and before any notation.
            insertion = next((i for i, child in enumerate(node) if child.tag == 'notations'), len(node))
            for level in range(1, column.levels + 1):
                bit = 1 << (level - 1)
                if before & bit:
                    value = 'continue' if after & bit else 'end'
                elif after & bit:
                    value = 'begin'
                else:
                    value = hook_levels.get((index, level))
                if value:
                    beam = Element('beam', number=str(level))
                    beam.text = value
                    node.insert(insertion, beam)
                    insertion += 1
