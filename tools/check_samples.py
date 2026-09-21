#!/usr/bin/env python3
"""Checks every sample in this repository, without knowing any of them by name.

Two properties this repository exists to guarantee, and therefore two things worth failing on:

1. **A sample builds from published binaries alone.** The vendored files must be present and must
   match the AEP version the sample declares. A sample declaring 0.5.1 while carrying 0.5.0
   binaries builds fine and tests the wrong thing.

2. **Experience rules stay renderer-independent** - ADR-0001 rule 2. The rule belongs to AEP, but
   the code satisfying it lives here, so it is enforced here. The platform repository is private
   and its check cannot be fetched from a public CI run, so this is a deliberate
   reimplementation rather than an oversight.

## Samples declare themselves

Discovery keys on `sample.json`, not on build files.

An earlier version looked for `settings.gradle.kts`, which meant a Unity sample - having no such
file - would have been **skipped in silence**. That is the precise failure this script was
written to avoid, reintroduced by inferring a sample's identity from one renderer's build system.
A directory that looks like a project but carries no `sample.json` is now a hard failure rather
than a quiet omission.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NOT_DOMAINS = {"tools", "docs"}

# Android: published components and the extension each ships as. Core is a JAR because nothing in
# it touches Android.
ANDROID_COMPONENTS = {
    "AEP.Core": "jar",
    "AEP.HostSDK": "aar",
    "AEP.AndroidTV.Adapter": "aar",
}

# Unity: managed plugins are identified by assembly name, so the filenames carry no version and a
# VERSION sidecar - written by the binary builder, read out of the compiled AEP.Core - carries it
# instead.
UNITY_PLUGINS = ["AEP.Core.dll", "AEP.HostSDK.dll", "AEP.Unity.Adapter.dll"]
UNITY_PLUGIN_DIR = "Assets/Plugins/AEP"

RENDERER_IMPORT_KOTLIN = re.compile(r"^\s*import\s+(android|androidx)\.", re.MULTILINE)
RENDERER_IMPORT_CSHARP = re.compile(r"^\s*using\s+UnityEngine", re.MULTILINE)

# A sample must not be able to reach AEP source. These are how each renderer would do it.
GRADLE_SOURCE_DEP = re.compile(r'project\(":aep[-.]')

# The AEP platform's Unity package IDs, listed exactly rather than matched by prefix. A prefix
# pattern flagged RockDodge's own package - com.ambokit.aep.unity - as platform source, which it
# is not. A sample is allowed to have its own package under the ambokit namespace; what it may
# not do is pull in the platform's.
AEP_PLATFORM_PACKAGES = {
    "com.ambokit.aep",
    "com.ambokit.unity-host",
    "com.ambokit.aep.unity-adapter",
    "com.ambokit.aep.binary-builder",
}

# Working files that must never reach a public reference sample. Not pedantry: these appear
# whenever someone renames or migrates in place, they are invisible in an editor, and a reader
# cannot tell a stale .pre-canonical.bak from the file that is actually compiled.
DEBRIS = ("*.bak", "*.orig", "*.rej", "*.csproj", "*.sln", "*.slnx", "*.user")

failures: list[str] = []
checks = 0


def fail(sample: str, message: str) -> None:
    failures.append(f"{sample}: {message}")
    print(f"FAIL  {sample}: {message}")


def ok(sample: str, message: str) -> None:
    global checks
    checks += 1
    print(f"ok    {sample}: {message}")


def find_samples() -> tuple[list[pathlib.Path], list[pathlib.Path]]:
    """Returns (samples, undeclared) - directories under a domain, split by whether they say so."""
    samples, undeclared = [], []
    for domain in sorted(p for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith(".")):
        if domain.name in NOT_DOMAINS:
            continue
        for candidate in sorted(p for p in domain.iterdir() if p.is_dir()):
            if (candidate / "sample.json").exists():
                samples.append(candidate)
            elif any(candidate.iterdir()):
                # Looks like something, declares nothing. Not skipped quietly.
                undeclared.append(candidate)
    return samples, undeclared


def check_android(sample: pathlib.Path, name: str, version: str) -> None:
    properties = sample / "gradle.properties"
    if not properties.exists():
        fail(name, "renderer is android-tv but there is no gradle.properties")
    else:
        match = re.search(r"^\s*aep\.version\s*=\s*(\S+)\s*$", properties.read_text(), re.MULTILINE)
        declared = match.group(1) if match else None
        if declared != version:
            fail(name, f"gradle.properties says aep.version={declared}, sample.json says {version}")
        else:
            ok(name, f"gradle.properties agrees on {version}")

    libs = sample / "libs"
    if not libs.is_dir():
        fail(name, "no libs/ directory; a sample must carry its own binaries")
        return

    for component, extension in ANDROID_COMPONENTS.items():
        expected = libs / f"{component}-{version}.{extension}"
        if not expected.exists():
            # Name the near-misses: a version bump that updated the declaration and forgot the
            # files is the likely cause, and saying so beats "file not found".
            siblings = sorted(p.name for p in libs.glob(f"{component}-*.{extension}"))
            detail = f" (found {', '.join(siblings)})" if siblings else ""
            fail(name, f"missing {expected.name}{detail}")
        elif expected.stat().st_size == 0:
            fail(name, f"{expected.name} is empty")
        else:
            ok(name, expected.name)


def check_unity(sample: pathlib.Path, name: str, version: str) -> None:
    plugins = sample / UNITY_PLUGIN_DIR
    if not plugins.is_dir():
        fail(name, f"no {UNITY_PLUGIN_DIR}/; a sample must carry its own binaries")
        return

    for plugin in UNITY_PLUGINS:
        dll = plugins / plugin
        if not dll.exists():
            fail(name, f"missing {UNITY_PLUGIN_DIR}/{plugin}")
        elif dll.stat().st_size == 0:
            fail(name, f"{plugin} is empty")
        else:
            ok(name, plugin)

    # Unity DLLs carry no version in their filenames, so the builder's VERSION sidecar is the only
    # thing that says which AEP these are. Without it the binaries are unidentifiable.
    version_file = plugins / "VERSION"
    if not version_file.exists():
        fail(name, f"no {UNITY_PLUGIN_DIR}/VERSION; the DLLs cannot be tied to an AEP version")
    else:
        carried = version_file.read_text().strip()
        if carried != version:
            fail(name, f"VERSION says {carried}, sample.json says {version}")
        else:
            ok(name, f"VERSION agrees on {version}")

    manifest = sample / "Packages" / "manifest.json"
    if not manifest.exists():
        fail(name, "no Packages/manifest.json")
        return
    try:
        dependencies = json.loads(manifest.read_text()).get("dependencies") or {}
    except json.JSONDecodeError as exc:
        fail(name, f"Packages/manifest.json is not valid JSON ({exc})")
        return
    source_packages = sorted(AEP_PLATFORM_PACKAGES & set(dependencies))
    if source_packages:
        fail(name, f"Packages/manifest.json pulls AEP source: {', '.join(source_packages)}")
    else:
        ok(name, "Packages/manifest.json pulls no AEP source")


def check_no_debris(sample: pathlib.Path, name: str) -> None:
    found = sorted(
        str(path.relative_to(sample))
        for pattern in DEBRIS
        for path in sample.rglob(pattern)
    )
    if found:
        shown = ", ".join(found[:6]) + (f" and {len(found) - 6} more" if len(found) > 6 else "")
        fail(name, f"working files that should not be published: {shown}")
    else:
        ok(name, "carries no backup or IDE files")


def check_no_platform_source(sample: pathlib.Path, name: str) -> None:
    """Gradle side of the same rule; the Unity side is checked against manifest.json above."""
    for build_file in sample.rglob("*.gradle.kts"):
        if "build" in build_file.parts:
            continue
        if GRADLE_SOURCE_DEP.search(build_file.read_text()):
            fail(name, f"{build_file.relative_to(sample)} depends on AEP platform source")
            return
    ok(name, "depends on no AEP platform source")


def check_experience_rules(sample: pathlib.Path, name: str, declared: object) -> None:
    """ADR-0001 rule 2, against the path the sample itself nominates.

    Required but nullable: a sample with no renderer-free rules must say so deliberately rather
    than have the check quietly find nothing to look at.
    """
    if declared is None:
        print(f"note  {name}: declares no renderer-free experience rules")
        return
    if not isinstance(declared, str):
        fail(name, f"experienceRules must be a path or null, got {declared!r}")
        return

    rules = sample / declared
    if not rules.is_dir():
        fail(name, f"experienceRules path does not exist: {declared}")
        return

    offenders = []
    for path in sorted(rules.rglob("*.kt")):
        if RENDERER_IMPORT_KOTLIN.search(path.read_text()):
            offenders.append(str(path.relative_to(sample)))
    for path in sorted(rules.rglob("*.cs")):
        if RENDERER_IMPORT_CSHARP.search(path.read_text(encoding="utf-8-sig")):
            offenders.append(str(path.relative_to(sample)))

    if offenders:
        fail(name, f"experience rules import a renderer: {', '.join(offenders)}")
    else:
        ok(name, "experience rules are renderer-independent")


def main() -> int:
    samples, undeclared = find_samples()

    for path in undeclared:
        fail(str(path.relative_to(ROOT)), "no sample.json - every sample must declare itself")

    if not samples:
        print("FAIL  no samples found - discovery is broken, or this repository is empty")
        return 1

    print(f"Checking {len(samples)} sample(s)\n")
    for sample in samples:
        name = sample.name
        try:
            declaration = json.loads((sample / "sample.json").read_text())
        except json.JSONDecodeError as exc:
            fail(name, f"sample.json is not valid JSON ({exc})")
            print()
            continue

        missing = [k for k in ("renderer", "aepVersion") if not declaration.get(k)]
        if missing:
            fail(name, f"sample.json is missing {', '.join(missing)}")
            print()
            continue
        if "experienceRules" not in declaration:
            fail(name, "sample.json must declare experienceRules (a path, or null)")
            print()
            continue

        renderer = declaration["renderer"]
        version = declaration["aepVersion"]
        print(f"      {sample.relative_to(ROOT)} - {renderer}, AEP {version}")

        if renderer == "android-tv":
            check_android(sample, name, version)
            check_no_platform_source(sample, name)
        elif renderer == "unity":
            check_unity(sample, name, version)
        else:
            fail(name, f"unknown renderer {renderer!r} - this check has no rules for it")

        check_no_debris(sample, name)
        check_experience_rules(sample, name, declaration["experienceRules"])
        print()

    if failures:
        print(f"{len(failures)} problem(s) across {len(samples)} sample(s)")
        return 1

    print(f"All {checks} checks passed across {len(samples)} sample(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
