#!/usr/bin/env python3
"""Estimate the plugin-hub AI reviewer's token count for this plugin.

The plugin-hub review bot refuses to run above 200,000 tokens. It counts *code*
under src/, excluding comments, and empirically does NOT count src/test.

Calibration (2026-07-31): the reviewer reported 200,915 tokens for master.
  src/main/java, comments stripped = 828,110 chars
  828,110 / 200,915                = 4.12 chars per token
Modelling src/main+src/test would imply 6.3 chars/token, which is not a
plausible rate for Java, so main-only at 4.12 is the working model. This script
reproduced 200,998 against their 200,915 -- a 0.04% error.

Re-calibrate CHARS_PER_TOKEN whenever the bot reports a fresh number:
    ./tools/token-budget.py --calibrate 200915

Two counter-intuitive rules this measures the consequences of:
  1. Splitting a file makes the count WORSE -- each new file adds a package
     declaration and its own import block. Merging small files recovers it.
  2. Comments and tests are free. Never delete either to buy budget.

Usage:
    ./tools/token-budget.py                          # totals + worst offenders
    ./tools/token-budget.py --baseline b.json --save # record a baseline
    ./tools/token-budget.py --baseline b.json        # diff against it
    ./tools/token-budget.py --ceiling 195000         # exit 1 if over (CI gate)
    ./tools/token-budget.py --baseline b.json --markdown  # PR-comment table
"""

import argparse
import json
import os
import re
import sys

CHARS_PER_TOKEN = 4.12
REVIEWER_LIMIT = 200_000

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"^\s*//.*$", re.M)


def strip_comments(source: str) -> str:
    """Drop comments the way the reviewer does before counting."""
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", source))


def measure(root: str) -> dict:
    """Return {relative_path: estimated_tokens} for every counted file."""
    src_main = os.path.join(root, "src", "main", "java")
    if not os.path.isdir(src_main):
        sys.exit(f"not a plugin checkout: {src_main} does not exist")

    files = {}
    for dirpath, _, filenames in os.walk(src_main):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8", errors="ignore") as handle:
                body = strip_comments(handle.read())
            rel = os.path.relpath(path, src_main).replace("com/flipsmart/", "")
            files[rel] = len(body) / CHARS_PER_TOKEN
    return files


def movement(files: dict, baseline: dict, threshold: int = 50) -> list:
    """Per-file deltas worth reporting, smallest (most negative) first."""
    moved = []
    for path in set(files) | set(baseline):
        delta = files.get(path, 0) - baseline.get(path, 0)
        if abs(delta) >= threshold:
            state = "removed" if path not in files else ("added" if path not in baseline else "")
            moved.append((delta, path, state))
    return sorted(moved)


def report(files: dict, top: int, ceiling: int) -> None:
    total = sum(files.values())
    headroom = ceiling - total
    state = "OVER" if headroom < 0 else "under"
    print(f"estimated tokens : {total:>10,.0f}")
    print(f"ceiling          : {ceiling:>10,}")
    print(f"headroom         : {headroom:>10,.0f}  ({state} ceiling)")
    print(f"reviewer limit   : {REVIEWER_LIMIT:>10,}")
    print(f"files counted    : {len(files):>10,}\n")

    ranked = sorted(files.items(), key=lambda kv: -kv[1])
    print(f"{'tokens':>9}  {'share':>6}  {'cumul':>6}  file")
    seen = 0.0
    for path, tokens in ranked[:top]:
        seen += tokens
        print(f"{tokens:9,.0f}  {100*tokens/total:5.1f}%  {100*seen/total:5.1f}%  {path}")


def diff(files: dict, baseline: dict) -> None:
    now, was = sum(files.values()), sum(baseline.values())
    print(f"\nbaseline         : {was:>10,.0f}")
    print(f"change           : {now-was:>+10,.0f} tokens")

    moved = movement(files, baseline)
    if not moved:
        return
    print("\nper-file movement (>=50 tokens):")
    for delta, path, state in moved:
        print(f"  {delta:>+9,.0f}  {path} {state}".rstrip())


def markdown(files: dict, baseline: dict | None, ceiling: int) -> str:
    """PR-comment table. Shows what this branch actually bought."""
    now = sum(files.values())
    out = ["<!-- token-budget -->", "### Plugin token budget", ""]

    if baseline is not None:
        was = sum(baseline.values())
        change = now - was
        arrow = "reduced" if change < 0 else ("increased" if change > 0 else "unchanged")
        out += [
            "| | tokens |",
            "|---|---:|",
            f"| base | {was:,.0f} |",
            f"| this branch | {now:,.0f} |",
            f"| **change** | **{change:+,.0f}** ({arrow}) |",
            f"| ceiling | {ceiling:,} |",
            f"| headroom | {ceiling-now:,.0f} ({100*(ceiling-now)/ceiling:.1f}%) |",
            "",
        ]
    else:
        out += [
            "| | tokens |",
            "|---|---:|",
            f"| this branch | {now:,.0f} |",
            f"| ceiling | {ceiling:,} |",
            f"| headroom | {ceiling-now:,.0f} ({100*(ceiling-now)/ceiling:.1f}%) |",
            "",
        ]

    if now > ceiling:
        out += [
            f"**Over the ceiling by {now-ceiling:,.0f} tokens.** Lower the count, or raise "
            + "the ceiling in `.github/token-ceiling` if the growth is deliberate.",
            "",
        ]

    if baseline is not None:
        moved = movement(files, baseline)
        if moved:
            out += ["<details><summary>Per-file movement (&ge;50 tokens)</summary>", "",
                    "| delta | file | |", "|---:|---|---|"]
            out += [f"| {d:+,.0f} | `{p}` | {s} |" for d, p, s in moved]
            out += ["", "</details>", ""]

    ranked = sorted(files.items(), key=lambda kv: -kv[1])[:10]
    out += ["<details><summary>Largest files on this branch</summary>", "",
            "| tokens | share | file |", "|---:|---:|---|"]
    out += [f"| {t:,.0f} | {100*t/now:.1f}% | `{p}` |" for p, t in ranked]
    out += ["", "</details>", "",
            "<sub>Comments and `src/test` are excluded -- the reviewer does not count them. "
            + "Splitting a file increases this number; merging small files reduces it.</sub>"]
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="plugin checkout (defaults to this script's repo)")
    parser.add_argument("--top", type=int, default=25, help="files to list")
    parser.add_argument("--baseline", help="baseline JSON to diff against or write")
    parser.add_argument("--save", action="store_true", help="write --baseline instead of diffing")
    parser.add_argument("--ceiling", type=int, default=REVIEWER_LIMIT,
                        help="fail with exit 1 above this count")
    parser.add_argument("--markdown", action="store_true", help="emit a PR-comment table")
    parser.add_argument("--calibrate", type=float, metavar="TOKENS",
                        help="print the chars/token implied by a reviewer-reported total")
    args = parser.parse_args()

    files = measure(args.root)

    if args.calibrate:
        chars = sum(files.values()) * CHARS_PER_TOKEN
        print(f"counted chars    : {chars:,.0f}")
        print(f"reviewer tokens  : {args.calibrate:,.0f}")
        print(f"implied ratio    : {chars/args.calibrate:.3f} chars/token")
        print(f"current constant : {CHARS_PER_TOKEN} -> update CHARS_PER_TOKEN if these diverge")
        return

    if args.baseline and args.save:
        with open(args.baseline, "w", encoding="utf-8") as handle:
            json.dump(files, handle, indent=1, sort_keys=True)
        print(f"baseline written : {args.baseline}  ({sum(files.values()):,.0f} tokens)")
        return

    baseline = None
    if args.baseline:
        with open(args.baseline, encoding="utf-8") as handle:
            baseline = json.load(handle)

    if args.markdown:
        print(markdown(files, baseline, args.ceiling))
    else:
        report(files, args.top, args.ceiling)
        if baseline is not None:
            diff(files, baseline)

    if sum(files.values()) > args.ceiling:
        sys.exit(1)


if __name__ == "__main__":
    main()
