#!/usr/bin/env python3
"""Tests for generate_release_notes.py."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "generate_release_notes.py"

_spec = importlib.util.spec_from_file_location("generate_release_notes", SCRIPT_PATH)
generate_release_notes = importlib.util.module_from_spec(_spec)
sys.modules["generate_release_notes"] = generate_release_notes  # for @dataclass
_spec.loader.exec_module(generate_release_notes)

Commit = generate_release_notes.Commit

# A history shaped like this repo's main branch: GitHub merge commits carrying
# the PR title on their first body line, plus a few direct pushes.
SAMPLE_HISTORY = [
    Commit("Merge pull request #530 from someone/feat/470", "feat: add vault export to PDF (#470)"),
    Commit("Merge pull request #529 from someone/fix/471", "fix(ios): keep biometric prompt visible after backgrounding"),
    Commit("Merge pull request #528 from someone/ci/472", "ci: cache SwiftPM packages"),
    Commit("Merge pull request #527 from someone/chore", "chore: bump dependencies"),
    Commit("Merge pull request #526 from someone/test", "test: cover offline queue"),
    Commit("Merge pull request #525 from someone/docs", "docs: update README"),
    Commit("Merge pull request #524 from someone/android", "fix(android): correct RTL padding on vault card"),
    Commit("Merge pull request #523 from someone/perf", "perf: speed up vault list rendering"),
    Commit("Merge branch 'main' into feat/470"),
    Commit("Merge pull request #522 from someone/batch", "fix: address #419, #420, #421"),
    Commit("Merge pull request #521 from someone/misc", "Battery drain"),
    Commit("feat: add vault export to PDF"),  # duplicate of #530
]


class ClassificationTests(unittest.TestCase):
    def test_keeps_features_fixes_and_perf_in_section_order(self):
        entries = generate_release_notes.collect_entries(SAMPLE_HISTORY)
        notes = generate_release_notes.build_notes(entries)
        self.assertEqual(
            notes,
            "New\n• Add vault export to PDF\n\n"
            "Improvements\n• Speed up vault list rendering\n\n"
            "Fixes\n• Keep biometric prompt visible after backgrounding",
        )

    def test_excludes_chore_ci_test_docs_and_non_conventional(self):
        notes = generate_release_notes.build_notes(
            generate_release_notes.collect_entries(SAMPLE_HISTORY)
        )
        for noise in ("SwiftPM", "dependencies", "offline queue", "README", "Battery drain"):
            self.assertNotIn(noise, notes)

    def test_excludes_android_only_scope_but_keeps_shared_scopes(self):
        classify = generate_release_notes.classify
        self.assertIsNone(classify(Commit("fix(android): correct padding")))
        self.assertIsNotNone(classify(Commit("fix(android,ios): correct padding")))
        self.assertIsNotNone(classify(Commit("fix(vault): correct padding")))

    def test_strips_issue_refs_and_drops_reference_only_entries(self):
        classify = generate_release_notes.classify
        self.assertEqual(classify(Commit("feat: add dark mode (#12)")).text, "Add dark mode")
        self.assertIsNone(classify(Commit("fix: address #419, #420, #421")))
        self.assertEqual(
            classify(Commit("feat: implement #439 #440 — widget dark mode")).text,
            "Implement widget dark mode",
        )
        self.assertEqual(
            classify(Commit("fix: resolve #1, #2 and #3: crash on launch")).text,
            "Resolve crash on launch",
        )
        # Dashes that are part of the sentence are kept.
        self.assertEqual(
            classify(Commit("feat: check-in reminders — now per vault")).text,
            "Check-in reminders — now per vault",
        )

    def test_ignores_sync_merges_and_empty_pr_merges(self):
        classify = generate_release_notes.classify
        self.assertIsNone(classify(Commit("Merge branch 'main' into feature")))
        self.assertIsNone(classify(Commit("Merge pull request #1 from a/b", "")))

    def test_breaking_change_marker_is_accepted(self):
        entry = generate_release_notes.classify(Commit("feat!: require iOS 17"))
        self.assertEqual(entry.text, "Require iOS 17")

    def test_no_user_facing_changes_falls_back_to_generic_text(self):
        notes = generate_release_notes.build_notes(
            generate_release_notes.collect_entries([Commit("chore: tidy")])
        )
        self.assertEqual(notes, generate_release_notes.EMPTY_NOTES)


class LengthLimitTests(unittest.TestCase):
    def test_trims_whole_bullets_from_the_end_to_fit_the_limit(self):
        commits = [Commit(f"feat: feature number {i} " + "x" * 80) for i in range(30)]
        commits += [Commit(f"fix: fix number {i} " + "y" * 80) for i in range(30)]
        entries = generate_release_notes.collect_entries(commits)
        notes = generate_release_notes.build_notes(entries, limit=1000)

        self.assertLessEqual(len(notes), 1000)
        self.assertTrue(notes.startswith("New\n"))
        # Fixes are the lowest-priority section, so they are dropped first...
        self.assertNotIn("Fixes", notes)
        # ...and every remaining line is a complete bullet.
        for line in notes.splitlines()[1:]:
            self.assertTrue(line.startswith("• Feature number") and line.endswith("x"))

    def test_default_limit_is_the_app_store_limit(self):
        commits = [Commit(f"feat: feature number {i} " + "x" * 200) for i in range(100)]
        notes = generate_release_notes.build_notes(generate_release_notes.collect_entries(commits))
        self.assertLessEqual(len(notes), 4000)
        self.assertGreater(len(notes), 3500)

    def test_single_oversized_entry_falls_back_instead_of_cutting(self):
        entries = generate_release_notes.collect_entries([Commit("feat: " + "z " * 100)])
        self.assertEqual(
            generate_release_notes.build_notes(entries, limit=50),
            generate_release_notes.EMPTY_NOTES,
        )


class LocaleAndOverrideTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_locales_come_from_metadata_dirs_with_default_first(self):
        for name in ("de-DE", "en-US", "fr-FR", "review_information"):
            (self.tmp / name).mkdir()
        (self.tmp / "README.md").write_text("docs")
        self.assertEqual(
            generate_release_notes.resolve_locales("en-US", str(self.tmp)),
            ["en-US", "de-DE", "fr-FR"],
        )

    def test_locales_without_metadata_dir_is_just_the_default(self):
        self.assertEqual(
            generate_release_notes.resolve_locales("en-US", str(self.tmp / "missing")),
            ["en-US"],
        )

    def test_every_locale_falls_back_to_the_default_text(self):
        notes = generate_release_notes.notes_by_locale(
            generated="generated", locales=["en-US", "de-DE"], default_locale="en-US",
        )
        self.assertEqual(notes, {"en-US": "generated", "de-DE": "generated"})

    def test_override_text_replaces_generated_text_for_all_locales(self):
        notes = generate_release_notes.notes_by_locale(
            generated="generated", locales=["en-US", "de-DE"], default_locale="en-US",
            override_text="  Hand-written notes.\n",
        )
        self.assertEqual(notes, {"en-US": "Hand-written notes.", "de-DE": "Hand-written notes."})

    def test_blank_override_text_is_ignored(self):
        notes = generate_release_notes.notes_by_locale(
            generated="generated", locales=["en-US"], default_locale="en-US",
            override_text="   \n",
        )
        self.assertEqual(notes, {"en-US": "generated"})

    def test_per_locale_override_files_win_and_default_override_is_the_fallback(self):
        (self.tmp / "en-US.txt").write_text("English notes\n", encoding="utf-8")
        (self.tmp / "de-DE.txt").write_text("Deutsche Notizen\n", encoding="utf-8")
        notes = generate_release_notes.notes_by_locale(
            generated="generated", locales=["en-US", "de-DE", "fr-FR"],
            default_locale="en-US", override_text="global", override_dir=str(self.tmp),
        )
        self.assertEqual(notes, {
            "en-US": "English notes",
            "de-DE": "Deutsche Notizen",
            "fr-FR": "English notes",
        })

    def test_override_over_the_limit_is_an_error_not_a_silent_cut(self):
        with self.assertRaises(ValueError):
            generate_release_notes.notes_by_locale(
                generated="generated", locales=["en-US"], default_locale="en-US",
                override_text="a" * 4001,
            )


class CommandLineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def run_main(self, *args):
        stdout = io.BytesIO()
        fake = io.TextIOWrapper(stdout, encoding="utf-8")
        with contextlib.redirect_stdout(fake), contextlib.redirect_stderr(io.StringIO()):
            code = generate_release_notes.main(list(args))
            fake.flush()
        return code, stdout.getvalue().decode("utf-8")

    def test_writes_a_file_per_locale_from_a_log_file(self):
        log = self.tmp / "log.json"
        log.write_text(json.dumps([{"subject": "feat: add widgets"}]), encoding="utf-8")
        (self.tmp / "metadata" / "de-DE").mkdir(parents=True)
        out = self.tmp / "out"

        code, stdout = self.run_main(
            "--output-dir", str(out), "--log-file", str(log),
            "--metadata-dir", str(self.tmp / "metadata"),
        )

        self.assertEqual(code, 0)
        self.assertEqual(stdout, "New\n• Add widgets\n")
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["de-DE.txt", "en-US.txt"])
        self.assertEqual((out / "de-DE.txt").read_text(encoding="utf-8"), "New\n• Add widgets\n")

    def test_oversized_override_file_exits_non_zero(self):
        log = self.tmp / "log.json"
        log.write_text("[]", encoding="utf-8")
        override = self.tmp / "override.txt"
        override.write_text("a" * 5000, encoding="utf-8")
        code, _ = self.run_main(
            "--output-dir", str(self.tmp / "out"), "--log-file", str(log),
            "--override-file", str(override),
        )
        self.assertEqual(code, 1)


@unittest.skipIf(shutil.which("git") is None, "git is not installed")
class GitHistoryTests(unittest.TestCase):
    def setUp(self):
        self.repo = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "Test")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True,
                              capture_output=True, text=True).stdout

    def commit(self, message):
        self.git("commit", "-q", "--allow-empty", "-m", message)

    def test_range_starts_at_the_previous_tag_and_excludes_the_release_tag(self):
        self.commit("feat: shipped in the first release")
        self.git("tag", "v1.0.0")
        self.commit("feat: shipped in the second release")
        self.git("tag", "v1.1.0")

        prev = generate_release_notes.previous_tag("HEAD", "v[0-9]*", cwd=str(self.repo))
        self.assertEqual(prev, "v1.0.0")
        commits = generate_release_notes.commits_from_git(prev, "HEAD", cwd=str(self.repo))
        self.assertEqual([c.subject for c in commits], ["feat: shipped in the second release"])

    def test_non_ascii_subjects_are_read_as_utf8(self):
        self.commit("feat: widget dark mode — Spotlight donations")
        commits = generate_release_notes.commits_from_git(None, "HEAD", cwd=str(self.repo))
        self.assertEqual(commits[0].subject, "feat: widget dark mode — Spotlight donations")

    def test_untagged_head_uses_the_latest_tag(self):
        self.commit("feat: first")
        self.git("tag", "v1.0.0")
        self.commit("fix: unreleased fix")
        self.assertEqual(
            generate_release_notes.previous_tag("HEAD", "v[0-9]*", cwd=str(self.repo)),
            "v1.0.0",
        )

    def test_no_tags_means_no_previous_tag(self):
        self.commit("feat: first")
        self.assertIsNone(
            generate_release_notes.previous_tag("HEAD", "v[0-9]*", cwd=str(self.repo))
        )


if __name__ == "__main__":
    unittest.main()
