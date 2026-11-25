#!/usr/bin/env python3
"""TarangFX versioning: the single implementation used by every CI workflow.

Policy
  * version.properties holds only `major=N`. Releases are git tags vMAJOR.MINOR.PATCH.
  * Each merge to main publishes the next version in the current major line:
      patch + 1 (default) or minor + 1 / patch 0 (PR labelled "semver:minor").
    Minor and patch are 0..99: x.y.99 + patch -> x.(y+1).0; x.99.99 cannot bump (a major
    release is required). The very first release of a major line is N.0.0.
  * A major release is a manual workflow. It requires version.properties to already say
    major = (latest released major + 1), merged through a pull request that changed only it.

Commands (stdlib only, so it runs anywhere CI does)
  validate [--changed-files FILE]   guard for pull requests and pushes to main
  next --bump patch|minor           next auto version (prints "major-pending" if a bump is waiting)
  major                             version for the manual major release
  code VERSION                      Android versionCode for VERSION
  selftest                          unit tests for the rules above
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VERSION_FILE = "version.properties"
GRADLE_FILE = os.path.join("app", "build.gradle.kts")
LIMIT = 100  # minor and patch are 0..LIMIT-1
TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


class PolicyError(Exception):
    pass


# ----------------------------------------------------------------------------------- pure logic
def parse_version_file(text: str) -> int:
    """Returns the major line. Only comments, blank lines and exactly one `major=N` are allowed."""
    major = None
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.fullmatch(r"major\s*=\s*(\d+)", line)
        if not m:
            raise PolicyError(f"{VERSION_FILE}:{n}: only 'major=<integer>' is allowed, found {line!r}")
        if major is not None:
            raise PolicyError(f"{VERSION_FILE}:{n}: 'major' is defined more than once")
        major = int(m.group(1))
    if major is None:
        raise PolicyError(f"{VERSION_FILE}: missing 'major=<integer>'")
    if major < 1:
        raise PolicyError(f"{VERSION_FILE}: major must be >= 1")
    return major


def parse_tags(tags: list[str]) -> list[tuple[int, int, int]]:
    out = []
    for t in tags:
        m = TAG_RE.match(t.strip())
        if m:
            out.append(tuple(int(x) for x in m.groups()))
    return sorted(out)


def fmt(v: tuple[int, int, int]) -> str:
    return "%d.%d.%d" % v


def version_code(v: tuple[int, int, int]) -> int:
    return v[0] * 10000 + v[1] * 100 + v[2]


def next_auto(major: int, released: list[tuple[int, int, int]], bump: str) -> tuple[int, int, int] | None:
    """Next version for a merge to main, or None when a major bump is pending (manual release)."""
    latest = released[-1] if released else None
    latest_major = latest[0] if latest else None
    if latest is None:
        return (major, 0, 0)  # very first release
    if major == latest_major + 1:
        return None  # major line advanced: only the manual major-release workflow may publish
    if major != latest_major:
        raise PolicyError(f"{VERSION_FILE} says major={major} but the latest release is {fmt(latest)}")
    _, minor, patch = latest
    if bump == "minor":
        minor, patch = minor + 1, 0
    elif bump == "patch":
        patch += 1
        if patch >= LIMIT:
            minor, patch = minor + 1, 0
    else:
        raise PolicyError(f"unknown bump {bump!r}")
    if minor >= LIMIT:
        raise PolicyError(f"{fmt(latest)} is the last version of major line {major}: run a major release")
    return (major, minor, patch)


def major_release(major: int, released: list[tuple[int, int, int]]) -> tuple[int, int, int]:
    latest = released[-1] if released else None
    if latest is None:
        raise PolicyError("no release exists yet: the first release is published automatically on merge")
    if major != latest[0] + 1:
        raise PolicyError(
            f"{VERSION_FILE} must say major={latest[0] + 1} (latest release {fmt(latest)}) "
            f"before a major release; it says major={major}")
    v = (major, 0, 0)
    if v in released:
        raise PolicyError(f"v{fmt(v)} already exists")
    return v


def validate(file_text: str | None, gradle_text: str, released: list[tuple[int, int, int]],
             changed: list[str] | None) -> list[str]:
    """Returns human-readable notes; raises PolicyError on any violation."""
    if file_text is None:
        raise PolicyError(f"{VERSION_FILE} is missing. It is required by the versioning policy.")
    major = parse_version_file(file_text)
    notes = [f"{VERSION_FILE}: major={major}"]
    for pat, what in ((r"versionCode\s*=\s*\d", "versionCode"), (r'versionName\s*=\s*"', "versionName")):
        if re.search(pat, gradle_text):
            raise PolicyError(f"{GRADLE_FILE} hard-codes {what}; versions must come from {VERSION_FILE} + CI")
    latest = released[-1] if released else None
    if latest is None:
        if major != 1:
            raise PolicyError(f"no release exists yet, so major must be 1 (found {major})")
        notes.append("no releases yet: the first merge publishes 1.0.0")
    elif major == latest[0]:
        notes.append(f"latest release v{fmt(latest)}; next merge publishes v{fmt(next_auto(major, released, 'patch'))} (patch)")
    elif major == latest[0] + 1:
        if changed is not None:
            others = sorted(f for f in changed if f != VERSION_FILE)
            if VERSION_FILE in changed and others:
                raise PolicyError("a major-version bump PR must change ONLY version.properties; also changed: "
                                  + ", ".join(others))
        notes.append(f"major bump to {major} pending: after merge, run the 'Major release' workflow")
    else:
        raise PolicyError(f"major={major} is not allowed: latest release is v{fmt(latest)}, so major must be "
                          f"{latest[0]} or {latest[0] + 1} (no skipping, no downgrades)")
    return notes


# ----------------------------------------------------------------------------------- git / IO
def git_tags() -> list[str]:
    out = subprocess.run(["git", "tag", "--list", "v*"], cwd=ROOT, capture_output=True, text=True, check=True)
    return out.stdout.split()


def read(path: str) -> str | None:
    p = os.path.join(ROOT, path)
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return f.read()


def set_output(**kv: str) -> None:
    """Writes step outputs for GitHub Actions (no-op locally)."""
    path = os.environ.get("GITHUB_OUTPUT")
    for k, v in kv.items():
        print(f"{k}={v}")
        if path:
            with open(path, "a", encoding="utf-8") as f:
                f.write(f"{k}={v}\n")


# ----------------------------------------------------------------------------------- tests
class PolicyTests(unittest.TestCase):
    R = staticmethod(parse_tags)

    def test_first_release_is_major_dot_zero(self):
        self.assertEqual(next_auto(1, [], "patch"), (1, 0, 0))

    def test_patch_and_minor_bumps(self):
        rel = self.R(["v1.0.0", "v1.0.1"])
        self.assertEqual(next_auto(1, rel, "patch"), (1, 0, 2))
        self.assertEqual(next_auto(1, rel, "minor"), (1, 1, 0))

    def test_rollover_at_100(self):
        self.assertEqual(next_auto(1, self.R(["v1.3.99"]), "patch"), (1, 4, 0))
        with self.assertRaises(PolicyError):
            next_auto(1, self.R(["v1.99.99"]), "patch")
        with self.assertRaises(PolicyError):
            next_auto(1, self.R(["v1.99.5"]), "minor")

    def test_tags_sorted_numerically_and_junk_ignored(self):
        rel = self.R(["v1.0.9", "v1.0.10", "nightly", "v1.2", "v1.0.2"])
        self.assertEqual(rel[-1], (1, 0, 10))

    def test_major_pending_blocks_auto_release(self):
        self.assertIsNone(next_auto(2, self.R(["v1.4.7"]), "patch"))

    def test_major_release(self):
        self.assertEqual(major_release(2, self.R(["v1.4.7"])), (2, 0, 0))
        for bad in (1, 3):
            with self.assertRaises(PolicyError):
                major_release(bad, self.R(["v1.4.7"]))
        with self.assertRaises(PolicyError):
            major_release(2, self.R(["v1.4.7", "v2.0.0"]))

    def test_version_file_format(self):
        self.assertEqual(parse_version_file("# c\n\nmajor=3\n"), 3)
        for bad in ("", "major=x", "major=1\nmajor=2", "major=1\nminor=2", "version=1.0.0", "major=0"):
            with self.assertRaises(PolicyError, msg=bad):
                parse_version_file(bad)

    def test_validate(self):
        g = "versionCode = tfxVersionCode\nversionName = tfxVersionName\n"
        rel = self.R(["v1.2.3"])
        validate("major=1", g, rel, ["app/x.kt"])
        validate("major=2", g, rel, ["version.properties"])
        with self.assertRaises(PolicyError):  # missing file
            validate(None, g, rel, [])
        with self.assertRaises(PolicyError):  # skipping a major
            validate("major=3", g, rel, ["version.properties"])
        with self.assertRaises(PolicyError):  # downgrade
            validate("major=0", g, rel, [])
        with self.assertRaises(PolicyError):  # major bump smuggled into a feature PR
            validate("major=2", g, rel, ["version.properties", "app/x.kt"])
        with self.assertRaises(PolicyError):  # hard-coded version in Gradle
            validate("major=1", 'versionName = "9.9.9"', rel, [])
        with self.assertRaises(PolicyError):  # first release must be major 1
            validate("major=2", g, [], [])

    def test_version_code(self):
        self.assertEqual(version_code((1, 0, 0)), 10000)
        self.assertEqual(version_code((2, 13, 7)), 21307)
        self.assertLess(version_code((1, 99, 99)), version_code((2, 0, 0)))


# ----------------------------------------------------------------------------------- CLI
def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("validate")
    v.add_argument("--changed-files", help="file with one changed path per line (pull requests)")
    n = sub.add_parser("next")
    n.add_argument("--bump", choices=["patch", "minor"], default="patch")
    sub.add_parser("major")
    c = sub.add_parser("code")
    c.add_argument("version")
    sub.add_parser("selftest")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "selftest":
            res = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PolicyTests))
            return 0 if res.wasSuccessful() else 1
        if a.cmd == "code":
            m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", a.version)
            if not m:
                raise PolicyError(f"not a version: {a.version}")
            print(version_code(tuple(int(x) for x in m.groups())))
            return 0
        released = parse_tags(git_tags())
        if a.cmd == "validate":
            changed = None
            if a.changed_files:
                with open(a.changed_files, encoding="utf-8") as f:
                    changed = [l.strip() for l in f if l.strip()]
            for note in validate(read(VERSION_FILE), read(GRADLE_FILE) or "", released, changed):
                print("OK:", note)
            return 0
        major = parse_version_file(read(VERSION_FILE) or "")
        if a.cmd == "next":
            nv = next_auto(major, released, a.bump)
            if nv is None:
                print(f"Major bump to {major} is pending: run the 'Major release' workflow.", file=sys.stderr)
                set_output(pending="true", version="")
            else:
                set_output(pending="false", version=fmt(nv), code=str(version_code(nv)))
            return 0
        if a.cmd == "major":
            mv = major_release(major, released)
            set_output(version=fmt(mv), code=str(version_code(mv)))
            return 0
    except PolicyError as e:
        print(f"::error title=Versioning policy::{e}" if os.environ.get("GITHUB_ACTIONS") else f"ERROR: {e}", file=sys.stderr)
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
