#!/usr/bin/env python3
"""Tests for docs/troubleshooting.md (issue #458).

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DOC_PATH = REPO_ROOT / "docs" / "troubleshooting.md"


class TroubleshootingGuideTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(DOC_PATH.is_file(), f"missing: {DOC_PATH}")
        self.text = DOC_PATH.read_text(encoding="utf-8")

    def test_file_exists_with_title(self):
        self.assertTrue(self.text.startswith("# Troubleshooting"))

    def test_common_issues_section(self):
        self.assertIn("## Common Issues", self.text)

    def test_diagnostic_steps_section(self):
        self.assertIn("## Diagnostic Steps", self.text)

    def test_solution_procedures_section(self):
        self.assertIn("## Solution Procedures", self.text)

    def test_decision_tree_section(self):
        self.assertIn("## Decision Tree", self.text)


if __name__ == "__main__":
    unittest.main()
