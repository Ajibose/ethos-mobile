#!/usr/bin/env python3
"""Generate App Store "What's New" text from conventional-commit history.

Used by the iOS release lanes (ios/EthosProtocol/fastlane/Fastfile) to produce
the TestFlight "What to Test" changelog and the App Store "What's New" text
for every locale in the store listing. See docs/ios-app-store-release.md.

Rules (deterministic, covered by tests/test_generate_release_notes.py):

* Commits are read from ``git log --first-parent <previous tag>..<to-ref>``,
  so each merged PR contributes a single entry. For GitHub merge commits
  ("Merge pull request #N from ...") the PR title on the first body line is
  used; "Merge branch ..." sync commits are ignored.
* Only user-facing conventional-commit types are kept: ``feat`` (New),
  ``perf`` (Improvements) and ``fix`` (Fixes). Everything else (chore, ci,
  test, docs, build, refactor, style, non-conventional subjects) is dropped.
* Entries scoped only to Android (``fix(android): ...``) are dropped, since
  these notes ship with the iOS build.
* Issue/PR references (``#123``) are stripped; entries with fewer than two
  words left (e.g. "fix: address #1, #2") carry no user-facing meaning and
  are dropped. Duplicates are collapsed.
* The text is trimmed to the App Store limit (4000 characters) by dropping
  whole bullets from the end, never by cutting a sentence mid-way.
* Overrides win over generated text: a per-locale override file
  (``<override-dir>/<locale>.txt``) beats a global override
  (``--override-file``), which beats generated text. Locales without their
  own text fall back to the default locale's text. Overrides longer than
  the limit are an error (a human-written text is never silently cut).

Usage:
    generate_release_notes.py --output-dir build/release_notes \\
        [--from-ref v1.2.0 | --tag-pattern 'v[0-9]*'] [--to-ref HEAD] \\
        [--metadata-dir fastlane/metadata] [--default-locale en-US] \\
        [--override-file notes.txt] [--override-dir fastlane/release_notes/1.3.0]
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

APP_STORE_LIMIT = 4000
DEFAULT_LOCALE = "en-US"
DEFAULT_TAG_PATTERN = "v[0-9]*"
EMPTY_NOTES = "Bug fixes and performance improvements."

# Conventional-commit type -> section heading, in display order.
SECTIONS = {
    "feat": "New",
    "perf": "Improvements",
    "fix": "Fixes",
}
BULLET = "• "

CONVENTIONAL_RE = re.compile(
    r"^(?P<type>[A-Za-z]+)(?:\((?P<scope>[^)]*)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$"
)
PR_MERGE_RE = re.compile(r"^Merge pull request #\d+ from \S+")
OTHER_MERGE_RE = re.compile(r"^Merge (branch|remote-tracking branch|tag) ")
# One issue reference or a list of them ("#1, #2 and #3"), plus a dash/colon
# that only introduced the list ("implement #439 #440 — widget dark mode").
ISSUE_REF_RE = re.compile(
    r"\(?#\d+\)?(?:[\s,&]+(?:and\s+)?\(?#\d+\)?)*(?:\s*[—–:-]\s+)?"
)
LOCALE_DIR_RE = re.compile(r"^[a-z]{2,3}(-[A-Za-z]{2,4})?$")


@dataclass(frozen=True)
class Commit:
    subject: str
    body: str = ""


@dataclass(frozen=True)
class Entry:
    section: str
    text: str


def pr_title(commit: Commit) -> str | None:
    """Returns the line that describes the change, or None for sync merges."""
    subject = commit.subject.strip()
    if PR_MERGE_RE.match(subject):
        for line in commit.body.splitlines():
            if line.strip():
                return line.strip()
        return None
    if OTHER_MERGE_RE.match(subject):
        return None
    return subject


def clean_description(desc: str) -> str:
    text = ISSUE_REF_RE.sub(" ", desc)
    text = re.sub(r"\s+,", ",", text)
    text = re.sub(r"[\s,;:—-]+$", "", text)
    text = re.sub(r"\s{2,}", " ", text).strip()
    text = text.rstrip(".").strip()
    if text:
        text = text[0].upper() + text[1:]
    return text


def is_android_only(scope: str | None) -> bool:
    if not scope:
        return False
    scopes = {s.strip().lower() for s in re.split(r"[,/ ]+", scope) if s.strip()}
    return "android" in scopes and "ios" not in scopes


def classify(commit: Commit) -> Entry | None:
    title = pr_title(commit)
    if not title:
        return None
    match = CONVENTIONAL_RE.match(title)
    if not match:
        return None
    kind = match.group("type").lower()
    if kind not in SECTIONS or is_android_only(match.group("scope")):
        return None
    text = clean_description(match.group("desc"))
    if len(text.split()) < 2:
        return None
    return Entry(section=SECTIONS[kind], text=text)


def collect_entries(commits: Iterable[Commit]) -> list[Entry]:
    seen: set[str] = set()
    entries: list[Entry] = []
    for commit in commits:
        entry = classify(commit)
        if entry is None:
            continue
        key = entry.text.casefold()
        if key in seen:
            continue
        seen.add(key)
        entries.append(entry)
    return entries


def render(entries: Sequence[Entry]) -> str:
    blocks = []
    for heading in SECTIONS.values():
        lines = [BULLET + e.text for e in entries if e.section == heading]
        if lines:
            blocks.append("\n".join([heading, *lines]))
    return "\n\n".join(blocks)


def build_notes(entries: Sequence[Entry], *, limit: int = APP_STORE_LIMIT,
                empty_text: str = EMPTY_NOTES) -> str:
    """Renders entries, dropping trailing bullets until the text fits `limit`."""
    kept = list(entries)
    # Drop from the lowest-priority section (the end of the rendered text) first.
    order = {heading: i for i, heading in enumerate(SECTIONS.values())}
    kept.sort(key=lambda e: order[e.section])
    text = render(kept)
    while kept and len(text) > limit:
        kept.pop()
        text = render(kept)
    # Nothing user-facing (or not even one bullet fits): use the generic text
    # rather than cutting an entry mid-sentence.
    return text if kept else empty_text


def check_length(text: str, source: str, limit: int) -> str:
    text = text.strip()
    if not text:
        raise ValueError(f"{source} is empty")
    if len(text) > limit:
        raise ValueError(
            f"{source} is {len(text)} characters; the App Store limit is {limit}"
        )
    return text


def resolve_locales(default_locale: str, metadata_dir: str | None,
                    extra: Sequence[str] = ()) -> list[str]:
    locales = {default_locale, *extra}
    if metadata_dir and Path(metadata_dir).is_dir():
        for child in Path(metadata_dir).iterdir():
            if child.is_dir() and LOCALE_DIR_RE.match(child.name):
                locales.add(child.name)
    return [default_locale] + sorted(locales - {default_locale})


def notes_by_locale(*, generated: str, locales: Sequence[str], default_locale: str,
                    override_text: str | None = None,
                    override_dir: str | None = None,
                    limit: int = APP_STORE_LIMIT) -> dict[str, str]:
    base = generated
    if override_text is not None and override_text.strip():
        base = check_length(override_text, "release notes override", limit)

    per_locale: dict[str, str] = {}
    if override_dir and Path(override_dir).is_dir():
        for locale in locales:
            path = Path(override_dir) / f"{locale}.txt"
            if path.is_file():
                per_locale[locale] = check_length(
                    path.read_text(encoding="utf-8"), str(path), limit
                )

    default_text = per_locale.get(default_locale, base)
    return {locale: per_locale.get(locale, default_text) for locale in locales}


def git(*args: str, cwd: str | None = None) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True,
        encoding="utf-8",
    ).stdout


def previous_tag(to_ref: str, pattern: str, cwd: str | None = None) -> str | None:
    """The newest tag matching `pattern` reachable from `to_ref`, excluding
    `to_ref` itself when it is the tag being released."""
    exact = subprocess.run(
        ["git", "describe", "--tags", "--exact-match", "--match", pattern, to_ref],
        cwd=cwd, capture_output=True, text=True, encoding="utf-8",
    )
    start = f"{to_ref}^" if exact.returncode == 0 else to_ref
    result = subprocess.run(
        ["git", "describe", "--tags", "--abbrev=0", "--match", pattern, start],
        cwd=cwd, capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip() or None


def commits_from_git(from_ref: str | None, to_ref: str,
                     cwd: str | None = None) -> list[Commit]:
    rev_range = f"{from_ref}..{to_ref}" if from_ref else to_ref
    raw = git("log", "--first-parent", "--format=%s%x1f%b%x1e", rev_range, cwd=cwd)
    commits = []
    for record in raw.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        subject, _, body = record.partition("\x1f")
        commits.append(Commit(subject=subject, body=body))
    return commits


def commits_from_file(path: str) -> list[Commit]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return [Commit(subject=item["subject"], body=item.get("body", "")) for item in data]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output-dir", required=True,
                        help="Writes <locale>.txt for every locale here.")
    parser.add_argument("--from-ref", help="Start of the range (exclusive). "
                        "Defaults to the previous tag matching --tag-pattern.")
    parser.add_argument("--to-ref", default="HEAD")
    parser.add_argument("--tag-pattern", default=DEFAULT_TAG_PATTERN)
    parser.add_argument("--log-file", help="JSON list of {subject, body} objects "
                        "to use instead of git history (for tests).")
    parser.add_argument("--default-locale", default=DEFAULT_LOCALE)
    parser.add_argument("--locales", default="",
                        help="Comma-separated locales in addition to the default "
                        "and any found in --metadata-dir.")
    parser.add_argument("--metadata-dir", help="fastlane deliver metadata directory; "
                        "each <locale>/ subdirectory gets release notes.")
    parser.add_argument("--override-file", help="Use this text for every locale "
                        "instead of the generated notes.")
    parser.add_argument("--override-dir", help="Directory of <locale>.txt files "
                        "that override individual locales.")
    parser.add_argument("--max-length", type=int, default=APP_STORE_LIMIT)
    args = parser.parse_args(argv)

    if args.log_file:
        commits = commits_from_file(args.log_file)
    else:
        from_ref = args.from_ref or previous_tag(args.to_ref, args.tag_pattern)
        if from_ref is None:
            print("warning: no previous release tag matching "
                  f"'{args.tag_pattern}'; using the full history. Consider a "
                  "release notes override for the first release.", file=sys.stderr)
        commits = commits_from_git(from_ref, args.to_ref)

    generated = build_notes(collect_entries(commits), limit=args.max_length)
    override_text = None
    if args.override_file:
        override_text = Path(args.override_file).read_text(encoding="utf-8")

    extra = [loc.strip() for loc in args.locales.split(",") if loc.strip()]
    locales = resolve_locales(args.default_locale, args.metadata_dir, extra)
    try:
        notes = notes_by_locale(
            generated=generated, locales=locales, default_locale=args.default_locale,
            override_text=override_text, override_dir=args.override_dir,
            limit=args.max_length,
        )
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    for locale, text in notes.items():
        (out / f"{locale}.txt").write_text(text + "\n", encoding="utf-8")
    # Encode explicitly: the bullet character breaks non-UTF-8 consoles (Windows).
    sys.stdout.buffer.write((notes[args.default_locale] + "\n").encode("utf-8"))
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
