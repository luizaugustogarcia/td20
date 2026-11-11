#!/usr/bin/env python3
"""Independently verify the local odd-length-cycle classification.

The half-turn and qualifying-move criteria are proved in the shared paper,
../td/docs/paper/main.tex, lem:qualifying-criterion and cor:half-turn.
The normalized DFS retains every cycle with no qualifying transposition.
Each retained cycle is checked against all applicable local transpositions.
Unpruned enumeration independently compares complete retained sets at lengths
5, 7, and 9. These are local candidates; companion target realizability and
support-clearing witnesses are separate obligations in the TD(20) paper.
The versioned JSON report goes to stdout; generated reports remain untracked.
"""
from itertools import combinations, permutations
import json
from math import gcd


def require(condition, detail):
    if not condition:
        raise ValueError(f'Local-cycle verification failed: {detail}')


EXPECTED_LOCAL = {5: [3], 7: [4], 9: [5, 7], 11: [6],
                  13: [7, 10, 11], 17: [9, 13, 15]}
EXPECTED_NODES = {5: 14, 7: 80, 9: 506, 11: 3391,
                  13: 23619, 17: 1243098}


def cycle_lengths(images):
    unseen = set(range(len(images)))
    lengths = []
    while unseen:
        symbol = min(unseen)
        length = 0
        while symbol in unseen:
            unseen.remove(symbol)
            length += 1
            symbol = images[symbol]
        lengths.append(length)
    return tuple(sorted(lengths))


def cycle_images(writing):
    images = [0] * len(writing)
    for index, symbol in enumerate(writing):
        images[symbol] = writing[(index + 1) % len(writing)]
    return tuple(images)


def has_qualifying_move(images):
    """Test every beta-applicable transposition on a single odd-length cycle."""
    require(len(images) % 2 == 1 and cycle_lengths(images) == (len(images),), "Expected one odd-length cycle")
    initial_odd = sum(length % 2 for length in cycle_lengths(images))
    for a, b, c in combinations(range(len(images)), 3):
        successor = list(images)
        successor[a], successor[b], successor[c] = images[c], images[a], images[b]
        lengths = cycle_lengths(successor)
        if 1 in lengths and sum(length % 2 for length in lengths) == initial_odd + 2:
            return True
    return False


def classify_local(length):
    """Enumerate each normalized cycle writing exactly once before pruning."""
    retained = []
    nodes = 0
    half_turn = (length + 1) // 2

    def ordered(a, b, c):
        return (b - a) % length < (c - a) % length

    def visit(path, used):
        nonlocal nodes
        nodes += 1
        if len(path) == length:
            if (path[0] - path[-1]) % length < half_turn:
                return
            if any(ordered(path[i], path[(i + 1) % length],
                           path[(i + m) % length])
                   for i in range(length) for m in range(2, length, 2)):
                return
            retained.append(cycle_images(path))
            return
        for symbol in range(1, length):
            if symbol in used or (symbol - path[-1]) % length < half_turn:
                continue
            depth = len(path)
            if any(ordered(path[depth - m], path[depth - m + 1], symbol)
                   for m in range(2, depth + 1, 2)):
                continue
            visit(path + [symbol], used | {symbol})

    visit([0], {0})
    require(nodes == EXPECTED_NODES[length], (length, nodes))
    exponents = []
    for images in retained:
        exponent = images[0]
        require(gcd(exponent, length) == 1, (length, exponent))
        require(images == tuple((i + exponent) % length for i in range(length)), "Non-power local cycle")
        require(not has_qualifying_move(images), (length, exponent))
        if exponent != length - 1:  # The unique unoriented local cycle.
            exponents.append(exponent)
    exponents.sort()
    require(exponents == EXPECTED_LOCAL[length], (length, exponents))
    require(len(retained) == len(exponents) + 1, "Missing unoriented local cycle")
    return {"length": length, "visited_nodes": nodes,
            "retained_including_unoriented": len(retained),
            "oriented_exponents": exponents}


def check_unpruned():
    lengths = (5, 7, 9)
    for length in lengths:
        retained = set()
        for tail in permutations(range(1, length)):
            images = cycle_images((0,) + tail)
            if not has_qualifying_move(images):
                retained.add(images)
        expected = {tuple((i + exponent) % length for i in range(length))
                    for exponent in EXPECTED_LOCAL[length] + [length - 1]}
        require(retained == expected, f'Unpruned length {length}')
    return list(lengths)


def main():
    rows = [classify_local(length) for length in sorted(EXPECTED_LOCAL)]
    small = check_unpruned()
    print(json.dumps({'format': 'td20-local-cycle-classification-v2',
                      'complete': True,
                      'scope': 'local candidates without qualifying transpositions',
                      'lengths': rows,
                      'unpruned_comparison_lengths': small,
                      'sorting_certificates_checked': False}, indent=2))


if __name__ == '__main__':
    main()
