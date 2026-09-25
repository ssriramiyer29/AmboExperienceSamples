#!/usr/bin/env python3
"""Canonical AEP experience manifests, validated (AEP-04).

An experience declares what it is and what it needs in code, to
`AepExperienceDefinition`. That works for the running app and for nothing else. A store
listing, a launcher, or a television deciding whether to offer an experience to the phone
in someone's hand all need the same facts *without* starting it — and one of those facts,
the oldest AEP release the experience runs against, cannot be expressed in code at all: a
build compiled against a newer AEP cannot tell you it would have failed against an older
one. AirDraw's dependency on 0.8.0 lived in a source comment.

`experience.json` is that declaration, and `schemas/aep-experience-manifest-v1.schema.json`
is its shape.

**This validator implements a subset of JSON Schema, on purpose.** Every other check in
this repository runs on the standard library alone, which means none of them can fail
because a package registry had a bad morning, and that property is worth more than the
keywords a full validator would add. The subset is made safe by one rule: **a schema
keyword this file does not implement is a hard failure**, naming the keyword. It can be
incomplete; it cannot be silently incomplete. Without that rule a hand-written validator
eventually reads a constraint it ignores and reports success.

What the schema cannot express, and this file checks afterwards:

- every capability id names a capability AmboKit actually publishes, at a major it
  publishes. AEP-04's instruction is to reuse AmboKit's ids rather than redefine them, and
  a typo is exactly how a redefinition starts;
- nothing is both required and optional;
- `aep.minimumVersion` is not a release that does not exist yet;
- where source sits beside a manifest, the code agrees with it.

  python3 tools/verify/check_manifests.py
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

# Defaults are this repository. They are overridable because the samples live in a separate,
# public repository that cannot check out this private one: it vendors this file and the
# schema beside its binaries, and points them at itself. One implementation, two trees - the
# alternative is two hand-written validators drifting apart, which is the exact failure this
# whole story is about.
ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "schemas" / "aep-experience-manifest-v1.schema.json"
CATALOGUE = ROOT / "protocol-schemas"
FIXTURES = ROOT / "conformance" / "manifests"
PROPERTIES = ROOT / "android" / "gradle.properties"

MANIFEST_NAME = "experience.json"

# Every keyword this validator honours. A schema using anything else stops the run rather
# than being partly enforced - see the module docstring.
UNDERSTOOD = {
    "$schema", "$id", "$defs", "$ref", "title", "description",
    "type", "required", "properties", "additionalProperties",
    "const", "enum", "pattern", "minLength",
    "items", "minItems", "uniqueItems",
}

TYPES = {
    "object": dict, "array": list, "string": str,
    "boolean": bool, "integer": int, "number": (int, float),
}


class SchemaUnsupported(Exception):
    """The schema asks for something this validator does not implement."""


# ---------------------------------------------------------------- schema validation

def resolve(node: dict, root: dict) -> dict:
    """Follow a local $ref. Only `#/$defs/<name>` is supported, and anything else says so."""
    ref = node["$ref"]
    if not ref.startswith("#/$defs/"):
        raise SchemaUnsupported(f"$ref {ref!r} is not a local #/$defs reference")
    name = ref[len("#/$defs/"):]
    if name not in root.get("$defs", {}):
        raise SchemaUnsupported(f"$ref {ref!r} does not exist")
    return root["$defs"][name]


def validate(value, node: dict, root: dict, where: str, problems: list[str]) -> None:
    unknown = set(node) - UNDERSTOOD
    if unknown:
        raise SchemaUnsupported(
            f"at {where}: schema keyword(s) {sorted(unknown)} are not implemented by this "
            f"validator. Implement them or the schema is only partly enforced."
        )

    if "$ref" in node:
        validate(value, resolve(node, root), root, where, problems)
        return

    if "const" in node and value != node["const"]:
        problems.append(f"{where}: must be {node['const']!r}, found {value!r}")
        return

    if "enum" in node and value not in node["enum"]:
        problems.append(f"{where}: must be one of {node['enum']}, found {value!r}")
        return

    if "type" in node:
        expected = TYPES[node["type"]]
        # JSON has one number type and Python spells booleans as ints; a boolean is not an
        # integer here, which matters because `schemaVersion: true` would otherwise pass.
        if isinstance(value, bool) != (node["type"] == "boolean"):
            problems.append(f"{where}: must be {node['type']}, found {type(value).__name__}")
            return
        if not isinstance(value, expected):
            problems.append(f"{where}: must be {node['type']}, found {type(value).__name__}")
            return

    if isinstance(value, str):
        if "pattern" in node and not re.search(node["pattern"], value):
            problems.append(f"{where}: {value!r} does not match {node['pattern']}")
        if "minLength" in node and len(value) < node["minLength"]:
            problems.append(f"{where}: shorter than {node['minLength']} characters")

    if isinstance(value, list):
        if "minItems" in node and len(value) < node["minItems"]:
            problems.append(f"{where}: needs at least {node['minItems']} item(s)")
        if node.get("uniqueItems") and len(value) != len({json.dumps(v, sort_keys=True) for v in value}):
            problems.append(f"{where}: contains duplicates")
        if "items" in node:
            for i, item in enumerate(value):
                validate(item, node["items"], root, f"{where}[{i}]", problems)

    if isinstance(value, dict):
        for name in node.get("required", []):
            if name not in value:
                problems.append(f"{where}: missing required field {name!r}")
        properties = node.get("properties", {})
        if node.get("additionalProperties") is False:
            for name in value:
                if name not in properties:
                    problems.append(f"{where}: unknown field {name!r}")
        for name, sub in properties.items():
            if name in value:
                validate(value[name], sub, root, f"{where}.{name}", problems)


# ---------------------------------------------------------------- semantic checks

def catalogue() -> dict[str, set[int]]:
    """The capabilities AmboKit publishes.

    Read from the vendored schemas by filename where they are present. A tree that has no
    reason to carry thirteen JSON Schema documents - the samples repository, which vendors
    binaries rather than protocol - may instead point `--catalogue` at a single JSON file
    of `{"camera.hand": [1], ...}`, written from those same schemas and carrying its own
    provenance. Either way the catalogue is AmboKit's, not a list someone typed.
    """
    found: dict[str, set[int]] = {}

    if CATALOGUE.is_file():
        document = json.loads(CATALOGUE.read_text(encoding="utf-8"))
        for name, majors in document.get("capabilities", {}).items():
            found[name.lower()] = {int(v) for v in majors}
        return found

    for path in CATALOGUE.glob("*-v*.schema.json"):
        match = re.match(r"^(.+)-v(\d+)\.schema\.json$", path.name)
        if match:
            found.setdefault(match.group(1).lower(), set()).add(int(match.group(2)))
    return found


def split_id(raw: str) -> tuple[str, int | None]:
    """The AepCapabilityId rule, in Python. Digits only, positive, or it is not a version."""
    at = raw.rfind("@")
    if at <= 0:
        return raw, None
    suffix = raw[at + 1:]
    if not suffix.isascii() or not suffix.isdigit() or int(suffix) <= 0:
        return raw, None
    return raw[:at], int(suffix)


def declared_version() -> tuple[int, ...] | None:
    # A tree with no gradle.properties - the samples repository - has no AEP version of its
    # own to compare a manifest's floor against. The floor is still checked for shape; there
    # is simply nothing here it could be ahead of.
    if not PROPERTIES.exists():
        return None
    match = re.search(r"^\s*aep\.version\s*=\s*(\S+)\s*$", PROPERTIES.read_text(), re.MULTILINE)
    if not match:
        return None
    core = match.group(1).split("-")[0]
    try:
        return tuple(int(p) for p in core.split("."))
    except ValueError:
        return None


def check_meaning(manifest: dict, known: dict[str, set[int]], where: str,
                  problems: list[str]) -> None:
    capabilities = manifest.get("capabilities")
    if not isinstance(capabilities, dict):
        return

    required = [c for c in capabilities.get("required", []) if isinstance(c, str)]
    optional = [c for c in capabilities.get("optional", []) if isinstance(c, str)]

    for raw in required + optional:
        name, major = split_id(raw)
        if name.lower() not in known:
            problems.append(
                f"{where}: {raw!r} is not a capability AmboKit publishes. Capability ids are "
                f"AmboKit's and are reused, not invented; the vendored catalogue is "
                f"protocol-schemas/.")
            continue
        if major is not None and major not in known[name.lower()]:
            offered = ", ".join(f"@{v}" for v in sorted(known[name.lower()]))
            problems.append(f"{where}: {raw!r} asks for major {major}; the catalogue has {offered}")

    # Two lists disagreeing about the same capability is not a preference, it is a mistake,
    # and which one wins would be decided by whichever consumer read them first.
    overlap = sorted({split_id(c)[0].lower() for c in required} &
                     {split_id(c)[0].lower() for c in optional})
    for name in overlap:
        problems.append(f"{where}: {name!r} is both required and optional")

    floor = manifest.get("aep", {}).get("minimumVersion") if isinstance(manifest.get("aep"), dict) else None
    current = declared_version()
    if isinstance(floor, str) and current and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", floor):
        if tuple(int(p) for p in floor.split(".")) > current:
            problems.append(
                f"{where}: needs AEP {floor}, which is ahead of this repository's "
                f"{'.'.join(str(p) for p in current)}")


# ------------------------------------------------- the code must agree with the manifest

DEFINITION = re.compile(r"AepExperienceDefinition\s*\((.*?)\)\s*,", re.S)

# Comments are stripped before the declaration is read. AirDraw's declaration carries three
# lines of commentary about why it needs camera.hand, and a comment that happened to quote a
# capability id would otherwise be read as declaring it.
COMMENTS = re.compile(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', re.S)


def strip_comments(source: str) -> str:
    return COMMENTS.sub(lambda m: m.group(0) if m.group(0).startswith('"') else " ", source)


def declarations_in(source: str) -> list[tuple[str, list[str]]]:
    """Every AepExperienceDefinition construction in a source file, as (id, capabilities)."""
    found = []
    for match in DEFINITION.finditer(strip_comments(source)):
        body = match.group(1)
        ids = re.findall(r'"([^"]+)"', body)
        if not ids:
            continue
        # The first string literal is the id; the rest are capability ids, on both renderers.
        found.append((ids[0], ids[1:]))
    return found


def check_code_agrees(manifest: dict, manifest_path: pathlib.Path, problems: list[str]) -> int:
    """Compare the manifest against any source sitting beside it.

    The point of a manifest is that something outside the app can trust it. A manifest that
    disagrees with the code is worse than no manifest, because it is believed.
    """
    sources = [p for p in manifest_path.parent.rglob("*")
               if p.suffix in (".kt", ".cs") and "AepExperienceDefinition" in p.read_text(errors="ignore")]
    if not sources:
        return 0

    where = manifest_path.relative_to(ROOT)
    declared_required = {split_id(c)[0].lower() for c in manifest.get("capabilities", {}).get("required", [])}
    declared_optional = {split_id(c)[0].lower() for c in manifest.get("capabilities", {}).get("optional", [])}
    checked = 0

    for source in sorted(sources):
        for experience_id, capabilities in declarations_in(source.read_text(errors="ignore")):
            checked += 1
            name = source.relative_to(ROOT)
            if experience_id != manifest.get("id"):
                problems.append(
                    f"{where}: declares id {manifest.get('id')!r} but {name} constructs "
                    f"{experience_id!r}. One experience, one id.")
            in_code = {split_id(c)[0].lower() for c in capabilities}
            missing = sorted(declared_required - in_code)
            if missing:
                problems.append(
                    f"{where}: declares {missing} as required, and {name} does not request "
                    f"them. A required capability the code never asks for is never granted.")
            undeclared = sorted(in_code - declared_required - declared_optional)
            if undeclared:
                problems.append(
                    f"{where}: {name} requests {undeclared}, which the manifest does not "
                    f"declare at all.")
    return checked


# ---------------------------------------------------------------- driving

def load(path: pathlib.Path, problems: list[str]) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        problems.append(f"{path.relative_to(ROOT)}: is not JSON ({exc})")
        return None


def main(argv: list[str] | None = None) -> int:
    global ROOT, SCHEMA, CATALOGUE, FIXTURES, PROPERTIES

    parser = argparse.ArgumentParser(description="Validate AEP experience manifests.")
    parser.add_argument("--root", type=pathlib.Path, help="tree to search for experience.json")
    parser.add_argument("--schema", type=pathlib.Path, help="the manifest schema")
    parser.add_argument("--catalogue", type=pathlib.Path,
                        help="directory of vendored AmboKit capability schemas")
    parser.add_argument("--fixtures", type=pathlib.Path,
                        help="directory holding valid/ and invalid/ manifest fixtures")
    parser.add_argument("--properties", type=pathlib.Path,
                        help="gradle.properties declaring aep.version; omit where there is none")
    args = parser.parse_args(argv)

    if args.root:
        ROOT = args.root.resolve()
        # Everything else defaults relative to the new root unless named explicitly.
        SCHEMA = ROOT / "schemas" / "aep-experience-manifest-v1.schema.json"
        CATALOGUE = ROOT / "protocol-schemas"
        FIXTURES = ROOT / "conformance" / "manifests"
        PROPERTIES = ROOT / "android" / "gradle.properties"
    if args.schema:
        SCHEMA = args.schema.resolve()
    if args.catalogue:
        CATALOGUE = args.catalogue.resolve()
    if args.fixtures:
        FIXTURES = args.fixtures.resolve()
    if args.properties:
        PROPERTIES = args.properties.resolve()

    problems: list[str] = []

    if not SCHEMA.exists():
        print(f"FAIL  {SCHEMA.relative_to(ROOT)} does not exist", file=sys.stderr)
        return 1
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    known = catalogue()
    if not known:
        print("FAIL  protocol-schemas/ holds no capability schemas; this check has gone stale",
              file=sys.stderr)
        return 1

    def check_one(manifest: dict, where: str) -> list[str]:
        found: list[str] = []
        validate(manifest, schema, schema, where, found)
        if not found:  # meaning is only worth checking once the shape is right
            check_meaning(manifest, known, where, found)
        return found

    # Real manifests, and the source beside them.
    real = sorted(p for p in ROOT.rglob(MANIFEST_NAME) if ".git" not in p.parts)
    agreements = 0
    for path in real:
        manifest = load(path, problems)
        if manifest is None:
            continue
        where = str(path.relative_to(ROOT))
        problems.extend(check_one(manifest, where))
        agreements += check_code_agrees(manifest, path, problems)

    # One experience is one experience, however many builds of it ship. RockDodge is an Android
    # TV app and a Unity app, built from separate trees, and they had drifted into two ids and
    # two different capability spellings without anything able to notice. Manifests sharing an
    # id must therefore agree about everything except which renderer they are for.
    by_id: dict[str, list[tuple[str, dict]]] = {}
    for path in real:
        manifest = load(path, problems)
        if isinstance(manifest, dict) and isinstance(manifest.get("id"), str):
            by_id.setdefault(manifest["id"], []).append((str(path.relative_to(ROOT)), manifest))

    shared = 0
    for experience_id, entries in sorted(by_id.items()):
        if len(entries) < 2:
            continue
        shared += 1
        (first_where, first), *rest = entries
        for where, other in rest:
            for field in ("name", "requiresCalibration", "capabilities", "aep"):
                mine, theirs = first.get(field), other.get(field)
                if field == "capabilities":
                    # Compared by capability name, since the two spellings are one capability.
                    mine, theirs = (
                        {k: sorted({split_id(c)[0].lower() for c in (v.get(k) or [])})
                         for k in ("required", "optional")}
                        for v in (first.get(field) or {}, other.get(field) or {}))
                if mine != theirs:
                    problems.append(
                        f"{where}: {experience_id!r} declares {field} as {theirs!r} here and "
                        f"{mine!r} in {first_where}. One experience does not need different "
                        f"things depending on which renderer it was built for.")

    # Fixtures: the valid ones must pass, and the invalid ones must fail for their own stated
    # reason. A validator is only as good as the things it rejects, and "it accepted everything
    # we gave it" is the failure mode a suite of valid examples cannot see.
    valid = sorted((FIXTURES / "valid").glob("*.json")) if (FIXTURES / "valid").is_dir() else []
    invalid = sorted((FIXTURES / "invalid").glob("*.json")) if (FIXTURES / "invalid").is_dir() else []

    for path in valid:
        manifest = load(path, problems)
        if manifest is not None:
            problems.extend(check_one(manifest, str(path.relative_to(ROOT))))

    for path in invalid:
        fixture = load(path, problems)
        if fixture is None:
            continue
        where = str(path.relative_to(ROOT))
        if not isinstance(fixture, dict) or "manifest" not in fixture or "expect" not in fixture:
            problems.append(f"{where}: an invalid fixture needs 'manifest', 'expect' and 'why'")
            continue
        found = check_one(fixture["manifest"], where)
        if not found:
            problems.append(f"{where}: was accepted, and it is meant to be rejected ({fixture.get('why')})")
        elif not any(fixture["expect"] in f for f in found):
            problems.append(
                f"{where}: rejected, but not for the stated reason. Expected something "
                f"containing {fixture['expect']!r}, got: {found}")

    # relative_to throws when the schema is not under the root, which is the normal case
    # whenever --root and --schema point into different trees - exactly what the samples
    # repository passes. A summary line must never be able to fail the run it is summarising.
    try:
        schema_label = SCHEMA.relative_to(ROOT)
    except ValueError:
        schema_label = SCHEMA
    print(f"Schema: {schema_label}")
    print(f"Capability catalogue: {len(known)} capabilities vendored")
    print(f"Manifests: {len(real)} real, {len(valid)} valid fixture(s), {len(invalid)} invalid fixture(s)")
    print(f"Declarations cross-checked against source: {agreements}")
    print(f"Experiences shipping on more than one renderer: {shared}")
    print()

    if not real and not valid:
        print("FAIL  nothing was validated; this check is reporting on an empty set",
              file=sys.stderr)
        return 1

    if problems:
        for problem in problems:
            print(f"FAIL  {problem}")
        print(f"\n{len(problems)} problem(s)")
        return 1

    print("ok    every manifest matches the schema, names only capabilities AmboKit publishes,")
    print("ok    and agrees with the code beside it")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SchemaUnsupported as exc:
        print(f"FAIL  {exc}", file=sys.stderr)
        sys.exit(1)
