#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Extract the merged Server's component-test inputs/assertions, never its implementation.

Usage: python3 scripts/au/regenerate-perform-fixtures.py ../tatrman-server
Fails closed if the fixture changes; this is deliberately not a Kotlin interpreter.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SHA = "c2f2f049d4c623ef06bd47d6879427d5ee7e95cf"
SOURCE = "services/automation/src/componentTest/kotlin/org/tatrman/automation/writer/ApplyComponentSpec.kt"
source = subprocess.check_output(["git", "-C", sys.argv[1], "show", f"{SHA}:{SOURCE}"]).decode()


def case(prefix):
    match = re.search(r'        "(' + re.escape(prefix) + r'[^\n]+)" \{\n(.*?)(?=\n        "|\Z)', source, re.S)
    if not match:
        raise SystemExit(f"Server fixture missing: {prefix}")
    return match.group(1), match.group(2)


def require(text, literal):
    if literal not in text:
        raise SystemExit(f"Server fixture changed: {literal}")


column = re.search(r'fun key\(order: String\) = buildJsonObject \{ put\("([^"]+)", order\) }', source).group(1)
dry_name, dry = case("R254-E: perform answers per key")
require(dry, 'answer.performed shouldBe emptyList()')
require(dry, 'hold("4711").map { it.copy(group = key("4711")) } + hold("4712")')
require(dry, 'actions = listOf(listOf(0, 1), listOf(2, 3))')
skipped = [{"key": {column: k}, "reason": r} for k, r in re.findall(r'SkippedKey\(key\("([^"]+)"\), "([^"]+)"\)', dry)]
if len(skipped) != 2:
    raise SystemExit("Server dry fixture no longer has two answers")
real_name, real = case("S2.3 T3: perform {keys}")
require(real, 'first.skipped shouldBe emptyList()')
require(real, 'actions = listOf(listOf(2, 3), listOf(4, 5))')
require(real, 'perform.perform(id, "hold", PerformRequest(keys = listOf(key("4711"))), identity.caller) shouldBe first')
key = re.search(r'first.performed.map \{ it.key } shouldBe listOf\(key\("([^"]+)"\)\)', real).group(1)
sealed = re.search(r'}.code shouldBe ErrorCode.(\w+)', real).group(1)
out_name, out = case("S2.3 T3: outcome appends")
count = int(re.search(r'\)\["outcomes"\] shouldBe JsonPrimitive\((\d+)\)', out).group(1))
item = re.search(r'OutcomeItem\(key\("([^"]+)"\), "([^"]+)", "([^"]+)"\)', out)
fixture = {
    "provenance": {"repository": "Collite/ttr-server", "sha": SHA, "file": SOURCE,
                   "sha256": hashlib.sha256(source.encode()).hexdigest(),
                   "cases": [dry_name, real_name, out_name],
                   "method": "source fixture inputs/assertions; not a live HTTP recording"},
    "dry": {"performed": [], "skipped": skipped},
    "real": {"performed": [{"key": {column: key}, "batchRows": [3, 4]}], "skipped": []},
    "outcome": {"request": {"outcomes": [{"key": {column: item[1]}, "status": item[2], "at": item[3]}]},
                "response": {"outcomes": count}, "sealed_error": sealed},
}
for relative in ("packages/kotlin/ttrp-conform/src/test/resources/replay/server-perform.json",
                 "packages/kotlin/ttr-emit-python/src/test/resources/python/server-perform.json"):
    (ROOT / relative).write_text(json.dumps(fixture, indent=2) + "\n")
print(f"Regenerated perform/outcome fixture assertions from {SHA}:{SOURCE}")
