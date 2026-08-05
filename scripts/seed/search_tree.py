"""search_tree.py — Mirror of MultiNoiseUtil$SearchTree from MC 1.21.1 (Yarn).

MIRRORS MultiNoiseUtil$SearchTree — change together.

Vanilla's biome source uses a spatial search tree (not a linear scan) for
nearest-neighbour biome lookup.  The tree's build order determines which
entry wins when two entries have identical quantised distance from a probe
point, because the traversal comparison is strict '<' (ifle in the
bytecode) — an equal distance does NOT replace the current best, so the
first-visited entry wins.

The tree operates in quantised long space: every coordinate and range
boundary is a long (the product of a float * 10000, truncated to int).
Distances are squared sums of per-axis ParameterRange.getDistance results.

Build algorithm (createNode, recursive):
  - 1 entry:  return it directly.
  - 2-6 entries: sort by sum-of-abs-midpoints across all 7 axes, make a
    flat branch.
  - >6 entries: for each of the 7 axes, sort by that axis (multi-key,
    wrapping to subsequent axes for tiebreak), batch into groups of
    6^floor(log6(size)), measure the total bounding-box span of the
    batches; pick the axis with the smallest total span; sort the
    batches by that axis (abs-midpoints), recurse each batch, assemble
    into a branch.

Traversal (getResultingNode):
  Iterate children in array order.  Prune a child if its bounding-box
  distance >= the current best distance.  Recurse into unpruned children.
  The comparison is strict '>' (Java bytecode: lcmp / ifle), meaning equal
  distance does NOT replace — first-visited wins.

lastResult (ThreadLocal<TreeLeafNode> previousResultNode):
  A per-thread hint that initialises the pruning bound.  It is a
  performance optimisation only: the bounding-box distance of a branch is
  a provable lower bound on every leaf it contains, so pruning never
  discards a leaf that could beat the current best.  The sampler mirrors
  independent lookups (no shared state between calls), equivalent to
  null-hint on every call — same results, no caching needed.
"""

import math

_PARAM_COUNT = 7
_MAX_NODES_FOR_SIMPLE_TREE = 6
_LONG_MAX = 0x7FFFFFFFFFFFFFFF


def _java_long_div(total, divisor):
    """Java's long division truncates toward zero; Python's // floors."""
    if total >= 0:
        return total // divisor
    return -((-total) // divisor)


def _midpoint(params, axis):
    """(min + max) / 2 for the given axis, with Java truncation semantics."""
    lo = params[axis * 2]
    hi = params[axis * 2 + 1]
    return _java_long_div(lo + hi, 2)


def _abs_midpoint_sum(params):
    """Sum of abs(midpoint) across all 7 axes.  Sort key for the <= 6 case."""
    total = 0
    for axis in range(_PARAM_COUNT):
        total += abs(_midpoint(params, axis))
    return total


def _sort_key_axis(params, axis, use_abs):
    """createNodeComparator: sort key for a single axis."""
    mid = _midpoint(params, axis)
    return abs(mid) if use_abs else mid


def _param_range_distance(noise_long, range_min, range_max):
    """ParameterRange.getDistance(long noise)."""
    above = noise_long - range_max
    below = range_min - noise_long
    return above if above > 0 else max(below, 0)


def _squared_distance(params, point):
    """TreeNode.getSquaredDistance(long[] point).

    params: tuple of 14 longs (7 axes x 2 min/max).
    point:  tuple of 7 longs.
    """
    dist = 0
    for i in range(_PARAM_COUNT):
        d = _param_range_distance(point[i], params[i * 2], params[i * 2 + 1])
        dist += d * d
    return dist


def _range_length_sum(params):
    """getRangeLengthSum: sum of abs(max - min) for each axis."""
    total = 0
    for i in range(_PARAM_COUNT):
        total += abs(params[i * 2 + 1] - params[i * 2])
    return total


def _enclosing_params(children_params):
    """getEnclosingParameters: bounding box of a list of param tuples."""
    result = list(children_params[0])
    for cp in children_params[1:]:
        for i in range(_PARAM_COUNT):
            lo_idx = i * 2
            hi_idx = lo_idx + 1
            if cp[lo_idx] < result[lo_idx]:
                result[lo_idx] = cp[lo_idx]
            if cp[hi_idx] > result[hi_idx]:
                result[hi_idx] = cp[hi_idx]
    return tuple(result)


# ---------------------------------------------------------------------------
# Tree nodes
# ---------------------------------------------------------------------------

class _Leaf:
    """TreeLeafNode: terminal node holding a biome value."""
    __slots__ = ('params', 'value')

    def __init__(self, params, value):
        self.params = params
        self.value = value

    def get_resulting(self, point, best_leaf, best_dist):
        return self, _squared_distance(self.params, point)


class _Branch:
    """TreeBranchNode: internal node with an array of children."""
    __slots__ = ('params', 'children')

    def __init__(self, params, children):
        self.params = params
        self.children = children

    def get_resulting(self, point, best_leaf, best_dist):
        for child in self.children:
            child_dist = _squared_distance(child.params, point)
            if best_dist > child_dist:
                candidate, cand_dist = child.get_resulting(
                    point, best_leaf, best_dist)
                if child is not candidate:
                    cand_dist = _squared_distance(candidate.params, point)
                if best_dist > cand_dist:
                    best_dist = cand_dist
                    best_leaf = candidate
        return best_leaf, best_dist


# ---------------------------------------------------------------------------
# Tree construction
# ---------------------------------------------------------------------------

def _sort_tree(nodes, start_axis, use_abs):
    """sortTree: stable multi-key sort, primary axis first.

    Java chains Comparator.comparingLong(axis0).thenComparing(axis1)...
    Python tuple-key sort is lexicographic — equivalent.
    """
    nodes.sort(key=lambda n: tuple(
        _sort_key_axis(n.params, (start_axis + k) % _PARAM_COUNT, use_abs)
        for k in range(_PARAM_COUNT)
    ))


def _get_batched(nodes):
    """getBatchedTree: split sorted nodes into groups.

    Batch size = (int)(6^floor(log(size - 0.01) / log(6))).
    """
    size = len(nodes)
    batch_size = int(math.pow(
        6.0, math.floor(math.log(size - 0.01) / math.log(6.0))))

    batches = []
    current = []
    for node in nodes:
        current.append(node)
        if len(current) >= batch_size:
            params = _enclosing_params([n.params for n in current])
            batches.append(_Branch(params, list(current)))
            current = []
    if current:
        params = _enclosing_params([n.params for n in current])
        batches.append(_Branch(params, list(current)))
    return batches


def _create_node(nodes):
    """createNode: recursively build the search tree."""
    if not nodes:
        raise ValueError("Need at least one child to build a node")

    if len(nodes) == 1:
        return nodes[0]

    if len(nodes) <= _MAX_NODES_FOR_SIMPLE_TREE:
        nodes.sort(key=lambda n: _abs_midpoint_sum(n.params))
        params = _enclosing_params([n.params for n in nodes])
        return _Branch(params, list(nodes))

    best_span = _LONG_MAX
    best_axis = -1
    best_batches = None

    for axis in range(_PARAM_COUNT):
        _sort_tree(nodes, axis, False)
        batches = _get_batched(nodes)
        total_span = sum(_range_length_sum(b.params) for b in batches)
        if best_span > total_span:
            best_span = total_span
            best_axis = axis
            best_batches = batches

    _sort_tree(best_batches, best_axis, True)
    children = [_create_node(list(b.children)) for b in best_batches]
    params = _enclosing_params([c.params for c in children])
    return _Branch(params, children)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

class SearchTree:
    """Mirror of MultiNoiseUtil$SearchTree.

    Build once from a list of (params_14_tuple, value) pairs, then call
    .get(point_7_tuple) to find the nearest value.  Ties are broken by
    tree visit order (first-visited wins, strict < comparison).
    """

    def __init__(self, entries):
        """Build the search tree.

        entries: iterable of (params, value) where params is a tuple of
                 14 longs (min0, max0, min1, max1, ..., min6, max6) for
                 the 7 NoiseHypercube axes (temperature, humidity,
                 continentalness, erosion, depth, weirdness, offset).
        """
        leaves = [_Leaf(p, v) for p, v in entries]
        if not leaves:
            raise ValueError(
                "Need at least one value to build the search tree.")
        self._root = _create_node(leaves)

    def get(self, point):
        """Find the nearest value to the given 7-long probe point.

        point: tuple of 7 longs (temperature, humidity, continentalness,
               erosion, depth, weirdness, 0).
        """
        leaf, _ = self._root.get_resulting(point, None, _LONG_MAX)
        return leaf.value
