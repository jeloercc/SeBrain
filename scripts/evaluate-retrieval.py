#!/usr/bin/env python3
"""Evaluate top-8 retrieval results against a real question set."""

import argparse
import csv
import sys
from pathlib import Path


def read_questions(path: Path) -> dict[str, str]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    required = {"id", "question", "expected_title"}
    if not rows or not required.issubset(rows[0]):
        raise ValueError(f"{path} must contain columns: {', '.join(sorted(required))}")
    questions = {}
    for row in rows:
        key = row["id"].strip()
        title = row["expected_title"].strip().casefold()
        if not key or not title or key in questions:
            raise ValueError(f"invalid or duplicate question id: {key!r}")
        questions[key] = title
    return questions


def read_results(path: Path, question_ids: set[str]) -> dict[str, list[tuple[int, str]]]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    required = {"id", "rank", "retrieved_title"}
    if not rows or not required.issubset(rows[0]):
        raise ValueError(f"{path} must contain columns: {', '.join(sorted(required))}")
    results: dict[str, list[tuple[int, str]]] = {}
    for row in rows:
        key = row["id"].strip()
        if key not in question_ids:
            raise ValueError(f"result references unknown question id: {key!r}")
        rank = int(row["rank"])
        if rank < 1:
            raise ValueError(f"rank must be positive for question {key!r}")
        results.setdefault(key, []).append((rank, row["retrieved_title"].strip().casefold()))
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("questions", type=Path)
    parser.add_argument("results", type=Path)
    parser.add_argument("--threshold", type=float, default=0.8)
    args = parser.parse_args()

    try:
        questions = read_questions(args.questions)
        results = read_results(args.results, set(questions))
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    missing = sorted(set(questions) - set(results))
    if missing:
        print(f"ERROR: missing results for {len(missing)} question(s): {', '.join(missing)}", file=sys.stderr)
        return 2

    hits = 0
    for key, expected in questions.items():
        top8 = {title for rank, title in results[key] if rank <= 8}
        hits += expected in top8
    rate = hits / len(questions)
    print(f"questions={len(questions)} hits_top8={hits} hit_rate={rate:.3f} threshold={args.threshold:.3f}")
    return 0 if rate >= args.threshold else 1


if __name__ == "__main__":
    raise SystemExit(main())
