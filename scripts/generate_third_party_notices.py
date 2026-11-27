#!/usr/bin/env python3
"""Generates THIRD_PARTY_NOTICES.md from the dependency metadata AboutLibraries writes during the build.

Usage (from the repository root):
    ./gradlew :app:prepareLibraryDefinitionsDebug
    python3 scripts/generate_third_party_notices.py            # writes THIRD_PARTY_NOTICES.md
    python3 scripts/generate_third_party_notices.py --check    # exits 1 if the file is out of date

The debug variant is used because its dependencies are a superset of the release variant's.
Output is deterministic (sorted, no timestamps) so CI can check it is up to date.
"""
import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_INPUT = ROOT / "app/build/generated/aboutLibraries/debug/res/raw/aboutlibraries.json"
OUTPUT = ROOT / "THIRD_PARTY_NOTICES.md"

HEADER = """\
# Third-party notices

GCode is licensed under the Apache License, Version 2.0 (see [LICENSE](LICENSE)).

GCode includes the third-party software listed below. The same list, with each licence's full
text, is shown in the app under **More > Open-source licences**.

This file is generated from the build's dependency metadata by
`scripts/generate_third_party_notices.py`. Do not edit it by hand; CI fails if it is out of date.

## Bundled assets

| Asset | Licence | Source |
|---|---|---|
| Material Symbols icons (vector drawables in `app/src/main/res/drawable`) | Apache-2.0 | https://github.com/google/material-design-icons |
| Full text of the Apache License 2.0 and the GNU LGPL 2.1 (`app/src/main/res/raw`) | (licence texts) | https://www.apache.org/licenses/LICENSE-2.0.txt, https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt |

## LGPL components

Libraries under the GNU Lesser General Public License are used as unmodified dependencies. Their
source code is available from the project websites listed below, and the full LGPL 2.1 text is
bundled in the app.

"""


def md_escape(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ").strip()


def render(data: dict) -> str:
    libraries = sorted(data.get("libraries", []), key=lambda lib: lib["uniqueId"])
    licenses = data.get("licenses", {})

    def license_names(lib: dict) -> str:
        names = []
        for key in lib.get("licenses", []):
            info = licenses.get(key, {})
            names.append(info.get("spdxId") or key)
        return ", ".join(sorted(names)) or "Unknown"

    out = [HEADER]
    lgpl = [lib for lib in libraries if "LGPL" in license_names(lib).upper()]
    if lgpl:
        for lib in lgpl:
            out.append(f"- {md_escape(lib.get('name') or lib['uniqueId'])} "
                       f"(`{lib['uniqueId']}:{lib.get('artifactVersion', '')}`): {license_names(lib)}, "
                       f"{lib.get('website') or (lib.get('scm') or {}).get('url', '')}\n")
    else:
        out.append("None in the current build. (Sora Editor, LGPL-2.1, is added with the code editor.)\n")

    out.append(f"\n## Libraries ({len(libraries)})\n\n")
    out.append("| Library | Artifact | Version | Licence |\n|---|---|---|---|\n")
    for lib in libraries:
        name = md_escape(lib.get("name") or lib["uniqueId"])
        website = lib.get("website") or (lib.get("scm") or {}).get("url")
        name_cell = f"[{name}]({website})" if website else name
        out.append(f"| {name_cell} | `{lib['uniqueId']}` | {md_escape(lib.get('artifactVersion', ''))} "
                   f"| {license_names(lib)} |\n")

    used = sorted({key for lib in libraries for key in lib.get("licenses", [])})
    out.append("\n## Licence texts\n\n")
    for key in used:
        info = licenses.get(key, {})
        title = info.get("name") or key
        spdx = info.get("spdxId") or key
        if spdx == "Apache-2.0":
            out.append(f"### {title}\n\nSee [LICENSE](LICENSE) (identical text).\n\n")
            continue
        content = (info.get("content") or "").strip()
        out.append(f"### {title}\n\n")
        out.append(f"```text\n{content}\n```\n\n" if content else f"{info.get('url', '')}\n\n")
    return "".join(out).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path, default=DEFAULT_INPUT)
    parser.add_argument("--check", action="store_true", help="fail if THIRD_PARTY_NOTICES.md is out of date")
    args = parser.parse_args()

    if not args.input.exists():
        print(f"error: {args.input} not found. Run ./gradlew :app:prepareLibraryDefinitionsDebug first.",
              file=sys.stderr)
        return 2
    rendered = render(json.loads(args.input.read_text(encoding="utf-8")))

    if args.check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current != rendered:
            print("error: THIRD_PARTY_NOTICES.md is out of date. Regenerate it with:\n"
                  "  ./gradlew :app:prepareLibraryDefinitionsDebug && "
                  "python3 scripts/generate_third_party_notices.py", file=sys.stderr)
            return 1
        print("THIRD_PARTY_NOTICES.md is up to date.")
        return 0

    OUTPUT.write_text(rendered, encoding="utf-8")
    print(f"Wrote {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
