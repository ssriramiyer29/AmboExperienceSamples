#!/usr/bin/env python3
"""Checks every sample in this repository, without knowing any of them by name.

Two properties this repository exists to guarantee, and therefore two things worth failing on:

1. **A sample builds from published binaries alone.** Vendored files must actually be present
   and must match the AEP version the sample declares. A sample whose gradle.properties says
   0.5.1 while libs/ holds 0.5.0 jars builds fine and tests the wrong thing.

2. **Experience rules stay renderer-independent** - ADR-0001 rule 2. The rule belongs to AEP,
   but the code satisfying it lives here, so it is enforced here. The platform repository is
   private and its check cannot be fetched from a public CI run, so this is a deliberate
   reimplementation rather than an oversight.

Samples are discovered, never listed. A hand-maintained list is a list that forgets the sample
added last Tuesday, and silence from a check that skipped everything looks exactly like
success.
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

# The three published components, and the extension each ships as. Core is a JAR because
# nothing in it touches Android.
COMPONENTS = {
    "AEP.Core": "jar",
    "AEP.HostSDK": "aar",
    "AEP.AndroidTV.Adapter": "aar",
}

RENDERER_IMPORT = re.compile(r"^\s*import\s+(android|androidx)\.", re.MULTILINE)

failures: list[str] = []
checks = 0


def fail(sample: str, message: str) -> None:
    failures.append(f"{sample}: {message}")
    print(f"FAIL  {sample}: {message}")


def ok(sample: str, message: str) -> None:
    global checks
    checks += 1
    print(f"ok    {sample}: {message}")


def find_samples() -> list[pathlib.Path]:
    """Any directory with a settings.gradle.kts, one level under a domain folder."""
    found = []
    for domain in sorted(p for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith(".")):
        if domain.name in {"tools", "docs"}:
            continue
        for candidate in sorted(domain.iterdir()):
            if (candidate / "settings.gradle.kts").exists():
                found.append(candidate)
    return found


def declared_version(sample: pathlib.Path) -> str | None:
    properties = sample / "gradle.properties"
    if not properties.exists():
        fail(sample.name, "no gradle.properties, so nothing declares which AEP it pins")
        return None
    match = re.search(r"^\s*aep\.version\s*=\s*(\S+)\s*$", properties.read_text(), re.MULTILINE)
    if match is None:
        fail(sample.name, "gradle.properties does not declare aep.version")
        return None
    return match.group(1)


def check_binaries(sample: pathlib.Path, version: str) -> None:
    libs = sample / "libs"
    if not libs.is_dir():
        fail(sample.name, "no libs/ directory; a sample must carry its own binaries")
        return

    for component, extension in COMPONENTS.items():
        expected = libs / f"{component}-{version}.{extension}"
        if not expected.exists():
            # Name the near-misses: a version bump that updated gradle.properties and forgot the
            # files is the likely cause, and saying so beats "file not found".
            siblings = sorted(p.name for p in libs.glob(f"{component}-*.{extension}"))
            detail = f" (found {', '.join(siblings)})" if siblings else ""
            fail(sample.name, f"missing {expected.name}{detail}")
            continue
        if expected.stat().st_size == 0:
            fail(sample.name, f"{expected.name} is empty")
            continue
        ok(sample.name, f"{expected.name}")


def check_no_platform_source(sample: pathlib.Path) -> None:
    """A sample that can reach AEP source is not proving anything."""
    for build_file in sample.rglob("*.gradle.kts"):
        if "build" in build_file.parts:
            continue
        text = build_file.read_text()
        if re.search(r'project\(":aep[-.]', text):
            fail(sample.name, f"{build_file.relative_to(sample)} depends on AEP platform source")
            return
    ok(sample.name, "depends on no AEP platform source")


def check_experience_rules(sample: pathlib.Path) -> None:
    """ADR-0001 rule 2, on whichever module holds the renderer-free rules."""
    core = sample / "core" / "src"
    if not core.is_dir():
        print(f"note  {sample.name}: no core/ module to check for renderer independence")
        return

    offenders = [
        str(path.relative_to(sample))
        for path in sorted(core.rglob("*.kt"))
        if RENDERER_IMPORT.search(path.read_text())
    ]
    if offenders:
        fail(sample.name, f"experience rules import a renderer: {', '.join(offenders)}")
    else:
        ok(sample.name, "experience rules are renderer-independent")


def main() -> int:
    samples = find_samples()
    if not samples:
        print("FAIL  no samples found - discovery is broken, or this repository is empty")
        return 1

    print(f"Checking {len(samples)} sample(s)\n")
    for sample in samples:
        version = declared_version(sample)
        if version is not None:
            print(f"      {sample.relative_to(ROOT)} pins AEP {version}")
            check_binaries(sample, version)
        check_no_platform_source(sample)
        check_experience_rules(sample)
        print()

    if failures:
        print(f"{len(failures)} problem(s) across {len(samples)} sample(s)")
        return 1

    print(f"All {checks} checks passed across {len(samples)} sample(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
