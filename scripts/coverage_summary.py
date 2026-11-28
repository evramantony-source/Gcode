#!/usr/bin/env python3
"""Prints line and branch coverage per module from JaCoCo XML reports (Markdown table).

Usage: python3 scripts/coverage_summary.py [module ...]   (default: agent gemini github git mcp)
"""
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
modules = sys.argv[1:] or ["agent", "gemini", "github", "git", "mcp"]

rows = []
for module in modules:
    path = os.path.join(ROOT, module, "build", "reports", "jacoco", "test", "jacocoTestReport.xml")
    if not os.path.exists(path):
        rows.append((module, None, None))
        continue
    report = ET.parse(path).getroot()
    counters = {c.get("type"): c for c in report.findall("counter")}

    def pct(kind):
        c = counters.get(kind)
        if c is None:
            return None
        covered, missed = int(c.get("covered")), int(c.get("missed"))
        return 100.0 * covered / (covered + missed) if covered + missed else 0.0

    rows.append((module, pct("LINE"), pct("BRANCH")))

print("| Module | Line coverage | Branch coverage |")
print("|---|---|---|")
for module, line, branch in rows:
    fmt = lambda v: "n/a" if v is None else f"{v:.1f}%"
    print(f"| :{module} | {fmt(line)} | {fmt(branch)} |")
