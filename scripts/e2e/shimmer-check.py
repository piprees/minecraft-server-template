#!/usr/bin/env python3
"""The portal's own draw rate against the client's, from a shimmer-trace run.

`stampFar` rises once per portal per frame that reaches the end of drawOne, so
differencing it against the wall clock gives the rate at which the projection
is actually drawn. Read it against fps: a portal drawing at a fraction of the
client's frame rate is being skipped by one of drawOne's early returns, and the
opening shows the source world in every frame it is skipped in.
"""
import json
import statistics
import sys


def rows(path):
    out = []
    for line in open(path):
        line = line.strip()
        if line:
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


def rate(samples, key):
    pairs = [(r["wall"], r[key]) for r in samples
             if isinstance(r.get("wall"), (int, float))
             and isinstance(r.get(key), (int, float))]
    pairs.sort()
    out = []
    for (w0, c0), (w1, c1) in zip(pairs, pairs[1:]):
        dt, dc = w1 - w0, c1 - c0
        if dt > 0 and dc >= 0:
            out.append((w0, dt, dc, dc / dt))
    return out


def main(path):
    samples = rows(path)
    if len(samples) < 2:
        print("  fewer than two samples — nothing to difference.")
        return
    span = samples[-1]["wall"] - samples[0]["wall"]
    print(f"  {len(samples)} samples over {span:.1f}s")

    for label, key in (("portal draws (DESTINATION_FAR)", "stampFar"),
                       ("near-depth stamps", "stampNear"),
                       ("far-depth stamps", "stampFarDepth")):
        intervals = rate(samples, key)
        if not intervals:
            print(f"  {label}: counter absent")
            continue
        rates = [r for _, _, _, r in intervals]
        stalled = [(w, dt) for w, dt, dc, _ in intervals if dc == 0]
        total = sum(dt for _, dt, _, _ in intervals)
        drawn = sum(dc for _, _, dc, _ in intervals)
        print(f"  {label}: {drawn} draws in {total:.1f}s"
              f" = {drawn/total:6.2f}/s   median={statistics.median(rates):6.2f}"
              f" min={min(rates):6.2f}")
        if stalled:
            lost = sum(dt for _, dt in stalled)
            print(f"    {len(stalled)} intervals drew NOTHING, {lost:.1f}s of"
                  f" {total:.1f}s — the opening showed the source world")

    fps = [r["fps"] for r in samples if isinstance(r.get("fps"), (int, float))]
    if fps:
        print(f"  client.getCurrentFps(): mean={statistics.fmean(fps):6.2f}"
              f" min={min(fps)} max={max(fps)}  <- a one-second mean")

    for key, label in (("meshReady", "mesh not ready"),
                       ("projections", "no projection held"),
                       ("slabProjections", "store empty")):
        if key == "meshReady":
            bad = [r for r in samples if r.get(key) is False]
        else:
            bad = [r for r in samples if r.get(key) == 0]
        if bad:
            print(f"  {label}: {len(bad)} of {len(samples)} samples"
                  f" (yaw {bad[0].get('yaw')} .. {bad[-1].get('yaw')})")

    planes = sorted({r.get("planes") for r in samples if r.get("planes") is not None})
    chunks = sorted({r.get("destinationChunks") for r in samples
                     if r.get("destinationChunks") is not None})
    quads = sorted({r.get("quads") for r in samples if r.get("quads") is not None})
    print(f"  clip planes seen: {planes}")
    print(f"  destinationChunks seen: {chunks}")
    print(f"  mesh quads seen: {quads}")
    emitted = [r["emitted"] for r in samples if isinstance(r.get("emitted"), int)]
    if emitted:
        print(f"  clipped quads emitted: min={min(emitted)} max={max(emitted)}"
              f"  zero in {sum(1 for e in emitted if e == 0)} samples")


if __name__ == "__main__":
    main(sys.argv[1])
