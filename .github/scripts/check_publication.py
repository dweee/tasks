#!/usr/bin/env python3
"""Check fork history without printing private identifiers or matching lines."""

import argparse
import ipaddress
import json
import os
import re
import subprocess
import sys

BASE = "28f55dcf8e"
IPV4 = re.compile(r"(?<![\w.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![\w.])")
IPV6 = re.compile(r"(?<![\w:])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![\w:])")
HOME = re.compile(r"/(?:home|Users)/[A-Za-z0-9_.-]+/")


def check_line(line, identifiers):
    rules = set()
    if HOME.search(line):
        rules.add("local home path")
    for pattern in (IPV4, IPV6):
        for match in pattern.finditer(line):
            try:
                ipaddress.ip_address(match.group())
            except ValueError:
                continue
            rules.add("IP address")
    for identifier in identifiers:
        if re.search(r"(?<![A-Za-z0-9])" + re.escape(identifier) + r"(?![A-Za-z0-9])", line, re.I):
            rules.add("private identifier")
    return rules


def check_history(base, head, identifiers, cwd=None):
    result = subprocess.run(
        ["git", "log", "--no-ext-diff", "--no-renames", "--format=%x1e%H%n%an <%ae>%n%cn <%ce>%n%B", "-p", f"{base}..{head}"],
        cwd=cwd, capture_output=True, text=True, errors="replace", check=True,
    )
    rules = set()
    in_patch = False
    for line in result.stdout.split("\n"):
        if line.startswith("\x1e"):
            in_patch = False
        elif line.startswith("diff --git "):
            in_patch = True
            rules.update(check_line(line, identifiers))
        elif not in_patch or (line.startswith("+") and not line.startswith("+++")):
            rules.update(check_line(line, identifiers))
    return rules


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args(argv)
    raw_policy = os.environ.get("PUBLICATION_DENYLIST", "")
    try:
        identifiers = json.loads(raw_policy) if raw_policy else []
        if not isinstance(identifiers, list) or any(not isinstance(item, str) or not item.strip() for item in identifiers):
            raise ValueError()
        if os.environ.get("REQUIRE_PRIVATE_POLICY") == "true" and not identifiers:
            raise ValueError()
    except (ValueError, TypeError):
        print("Publication blocked: private policy is missing or invalid.", file=sys.stderr)
        return 1
    try:
        rules = check_history(args.base, args.head, identifiers)
    except subprocess.CalledProcessError:
        print("Publication blocked: cannot inspect the requested Git history.", file=sys.stderr)
        return 1
    if rules:
        print("Publication blocked: " + ", ".join(sorted(rules)) + ". Matching values are withheld.", file=sys.stderr)
        return 1
    print("Publication privacy check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
