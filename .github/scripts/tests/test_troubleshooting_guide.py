#!/usr/bin/env python3
"""Tests for docs/troubleshooting.md (issue #458).

Validates that the troubleshooting guide exists, covers the four required
areas from the issue (common issues, diagnostic steps, solution procedures,
decision tree), keeps its decision-tree procedures consistent, and only
references documentation files that actually exist in the repository.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v

Or directly:
    python3 .github/scripts/tests/test_troubleshooting_guide.py
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DOC_PATH = REPO_ROOT / "docs" / "troubleshooting.md"


class TroubleshootingGuideStructureTests(unittest.TestCase):
    """The guide must exist and expose the sections required by issue #458."""

    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")

    def test_file_exists_with_title(self):
        self.assertTrue(self.text.startswith("# Troubleshooting"))

    def test_has_intro_explaining_usage_order(self):
        """The intro should tell readers how to use the guide."""
        self.assertIn("Decision Tree", self.text)
        intro = self.text[: self.text.index("## Decision Tree")]
        # The intro lists the guide's components in usage order.
        for anchor in ("Decision Tree", "Common Issues", "Diagnostic Steps", "Solution Procedures"):
            self.assertIn(anchor, intro)

    def test_common_issues_section(self):
        self.assertIn("## Common Issues", self.text)

    def test_diagnostic_steps_section(self):
        self.assertIn("## Diagnostic Steps", self.text)

    def test_solution_procedures_section(self):
        self.assertIn("## Solution Procedures", self.text)

    def test_decision_tree_section(self):
        self.assertIn("## Decision Tree", self.text)

    def test_required_sections_come_before_quick_reference(self):
        """Canonical reading order: decision tree → common issues → diagnostics → procedures → reference."""
        order = [
            self.text.index("## Decision Tree"),
            self.text.index("## Common Issues"),
            self.text.index("## Diagnostic Steps"),
            self.text.index("## Solution Procedures"),
            self.text.index("## Quick Reference"),
        ]
        self.assertEqual(order, sorted(order))


class DecisionTreeTests(unittest.TestCase):
    """The decision tree is the triage entry point; it must stay consistent."""

    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")

    @property
    def tree_block(self) -> str:
        start = self.text.index("## Decision Tree")
        end = self.text.index("## Common Issues")
        return self.text[start:end]

    def test_tree_covers_headline_symptom_families(self):
        for family in (
            "sign in",
            "HTTP error",
            "Offline",
            "cert change",
            "Build / CI failure",
        ):
            self.assertIn(family, self.tree_block)

    def test_every_procedure_referenced_from_tree_is_defined(self):
        referenced = set(re.findall(r"P\d+", self.tree_block))
        self.assertTrue(referenced, "decision tree must reference procedures")
        defined = set(re.findall(r"^### (P\d+)\.", self.text, flags=re.MULTILINE))
        self.assertEqual(referenced, defined, "tree references and defined procedures must match")

    def test_procedures_are_sequentially_numbered(self):
        defined = re.findall(r"^### (P\d+)\.", self.text, flags=re.MULTILINE)
        numbers = [int(p[1:]) for p in defined]
        self.assertEqual(numbers, list(range(1, len(numbers) + 1)))

    def test_tree_references_point_to_procedures_section(self):
        anchor = self.text.index("## Solution Procedures")
        self.assertIn("[Solution Procedures](#solution-procedures)", self.tree_block)
        self.assertLess(self.text.index("## Decision Tree"), anchor)


class CommonIssuesTests(unittest.TestCase):
    """Common issues must map symptoms to causes, fixes, and procedures."""

    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")
        start = self.text.index("## Common Issues")
        end = self.text.index("## Diagnostic Steps")
        self.section = self.text[start:end]

    def test_section_is_organized_by_symptom_category(self):
        self.assertIn("### Sign-in and authentication", self.section)
        self.assertIn("### Request failures", self.section)
        self.assertIn("### Offline and sync", self.section)
        self.assertIn("### Connectivity and certificates", self.section)
        self.assertIn("### Build and CI", self.section)

    def test_tables_have_required_columns(self):
        for header in ("| Symptom |", "| Usual cause |", "| Fast fix |", "| Details |"):
            self.assertIn(header, self.section)

    def test_every_details_reference_is_a_defined_procedure(self):
        referenced = set(re.findall(r"\| (P\d+) \|", self.section))
        defined = set(re.findall(r"^### (P\d+)\.", self.text, flags=re.MULTILINE))
        self.assertTrue(referenced, "common-issues tables must reference procedures")
        self.assertLessEqual(referenced, defined, "Details column must point at defined procedures")


class DiagnosticStepsTests(unittest.TestCase):
    """Diagnostics must be an ordered, executable checklist."""

    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")
        start = self.text.index("## Diagnostic Steps")
        end = self.text.index("## Solution Procedures")
        self.section = self.text[start:end]

    def test_steps_are_numbered_and_ordered(self):
        numbers = re.findall(r"^(\d+)\. \*\*", self.section, flags=re.MULTILINE)
        self.assertTrue(numbers, "diagnostic steps must be a numbered list")
        self.assertEqual([int(n) for n in numbers], list(range(1, len(numbers) + 1)))

    def test_includes_error_code_lookup_tool(self):
        self.assertIn("scripts/lookup_error_code.py", self.section)

    def test_includes_smoke_test_verification(self):
        self.assertIn("scripts/smoke_test_staging.sh", self.section)

    def test_includes_log_collection_and_escalation(self):
        self.assertIn("Collect logs", self.section)
        self.assertIn("Escalate", self.section)


class SolutionProceduresTests(unittest.TestCase):
    """Each procedure must be concrete and actionable."""

    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")
        start = self.text.index("## Solution Procedures")
        end = self.text.index("## Quick Reference")
        self.section = self.text[start:end]

    def procedures(self) -> dict[str, str]:
        """Map each procedure ID to its body text."""
        matches = list(re.finditer(r"^### (P\d+)\. (.+)$", self.section, flags=re.MULTILINE))
        result: dict[str, str] = {}
        for i, match in enumerate(matches):
            body_end = matches[i + 1].start() if i + 1 < len(matches) else len(self.section)
            result[match.group(1)] = self.section[match.end() : body_end]
        return result

    def test_procedure_bodies_are_substantive(self):
        for pid, body in self.procedures().items():
            steps = re.findall(r"^\d+\. ", body, flags=re.MULTILINE)
            self.assertGreaterEqual(len(steps), 2, f"{pid} needs at least 2 numbered steps")

    def test_critical_procedures_reference_runbooks(self):
        self.assertIn("docs/cert-pin-rotation-runbook.md", self.procedures()["P8"])
        self.assertIn("docs/staging-environment.md", self.procedures()["P11"])

    def test_passkey_procedure_matches_otp_cooldown_schedule(self):
        """P2 must repeat the documented OTP cooldown schedule accurately."""
        body = self.procedures()["P2"]
        for value in ("30 s", "60 s", "120 s"):
            self.assertIn(value, body)

    def test_no_leftover_placeholder_content(self):
        # "placeholder" is excluded: it legitimately appears when describing
        # Android's placeholder DEFAULT_PINS in procedure P8.
        for marker in ("TODO", "TBD", "FIXME", "work in progress", "to be written"):
            self.assertNotIn(marker, self.text.lower())


class CrossReferenceTests(unittest.TestCase):
    """Relative doc links in the guide must resolve to real files."""

    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")

    def test_all_relative_markdown_links_resolve(self):
        links = re.findall(r"\]\(([^)#]+?\.md)\)", self.text)
        self.assertTrue(links, "guide should cross-reference existing docs")
        for link in links:
            target = (REPO_ROOT / "docs" / link).resolve()
            self.assertTrue(target.is_file(), f"broken relative link in guide: {link}")


if __name__ == "__main__":
    unittest.main()
