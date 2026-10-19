# SPDX-License-Identifier: Apache-2.0
"""Grammar 0.16 — Python parity for the automation model (AU-core-P0 S0.2).

Mirrors the TS ``automation-grammar.test.ts`` and the Kotlin ``AutomationParseSpec.kt``: the design
fixtures (``17`` §1–§2 as written for 0.16) parse with zero errors; the parser-level rejects fail.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from ttr_parser import parse_string
from ttr_parser.model import (
    AutoDuration,
    AutoId,
    AutomationDef,
    AutoNum,
    AutoStar,
    AutoTagged,
    EntityDef,
    RoleDef,
    SchemaDef,
    TargetReferenceValue,
    WorldDef,
)

_RESOURCES = Path(__file__).resolve().parents[3] / "kotlin" / "ttr-parser" / "src" / "test" / "resources"


def _parse(rel: str):  # type: ignore[no-untyped-def]
    return parse_string((_RESOURCES / rel).read_text(encoding="utf-8"), rel)


def _entry(body_entries, key):  # type: ignore[no-untyped-def]
    return next((e.value for e in reversed(body_entries) if e.key == key), None)


def test_automation_fixture_parameters_actions_roles_approvals() -> None:
    r = _parse("automation/automation.ttrm")
    assert r.errors == ()
    assert r.model_directive is not None and r.model_directive.model_code == "automation"
    autos = [d for d in r.definitions if isinstance(d, AutomationDef)]
    assert len([a for a in autos if a.kind == "parameter"]) == 9
    assert len([a for a in autos if a.kind == "action"]) == 10
    assert len([a for a in autos if a.kind == "approval"]) == 4
    assert len([d for d in r.definitions if isinstance(d, RoleDef)]) == 8

    tolerance = next(a for a in autos if a.name == "tolerance")
    assert tolerance.body.type is not None and tolerance.body.type.name == "decimal"
    value = _entry(tolerance.body.entries, "value")
    assert isinstance(value, AutoNum) and value.raw == 0.01

    hold = next(a for a in autos if a.name == "hold")
    assert [c.name for c in hold.body.columns] == ["order", "department"]
    assert hold.body.set is not None and (hold.body.set.attribute, hold.body.set.column) == ("order.held_for", "department")
    assert hold.body.transition is not None
    assert hold.body.transition.to == "held" and hold.body.transition.from_states == ("placed",)

    set_state = next(a for a in autos if a.name == "set_state")
    assert set_state.body.transition is not None
    assert set_state.body.transition.to_column == "to" and set_state.body.transition.to is None

    due = _entry(next(a for a in autos if a.name == "ask").body.entries, "due")
    assert isinstance(due, AutoDuration) and (due.amount, due.unit) == (2.0, "working_days")

    sales = next(d for d in r.definitions if isinstance(d, RoleDef) and d.name == "sales")
    assert [e.key for e in sales.entries] == ["resolve", "delivery"]
    resolve = sales.entries[0].value
    assert isinstance(resolve, AutoTagged) and (resolve.tag, resolve.value) == ("keycloak_group", "hartland-sales")


def test_programs_fixture_programs_and_event_schemas() -> None:
    r = _parse("automation/programs.ttrm")
    assert r.errors == ()
    programs = [d for d in r.definitions if isinstance(d, AutomationDef) and d.kind == "program"]
    assert len(programs) == 8
    decide = next(p for p in programs if p.name == "decide_on_request")
    event = _entry(decide.body.entries, "event")
    assert isinstance(event, AutoId) and event.parts == ("schema", "line_received")
    assert len([d for d in r.definitions if isinstance(d, SchemaDef)]) == 3


def test_demo_world_members_nest_in_the_world() -> None:
    r = _parse("automation/demo-world.ttrm")
    assert r.errors == ()
    world = next(d for d in r.definitions if isinstance(d, WorldDef))
    assert [m.name for m in world.identities] == ["automation_decider"]
    assert len(world.grants) == 3
    assert world.grants[0].name is None
    assert isinstance(_entry(world.grants[0].body.entries, "program"), AutoStar)
    assert len(world.deployments) == 12
    morning = next(d for d in world.deployments if d.name == "release_native_morning")
    assert isinstance(morning.body.target, TargetReferenceValue)
    trigger = _entry(morning.body.entries, "trigger")
    assert isinstance(trigger, AutoTagged) and trigger.tag == "schedule"
    assert [e.extends for e in world.executors] == ["n8n_l1", "camunda_8", "python_client"]
    assert [s.name for s in world.processes[0].steps] == ["decide", "inbound", "settle"]


def test_commaless_literal_then_key_is_not_a_duration() -> None:
    r = parse_string('model automation\ndef parameter p { value: 0.01 label: { en: "P" } quorum: 2 days }', "p.ttrm")
    assert r.errors == ()
    p = r.definitions[0]
    assert isinstance(p, AutomationDef)
    assert isinstance(_entry(p.body.entries, "value"), AutoNum)
    assert p.body.label is not None and dict(p.body.label.by_language) == {"en": "P"}
    quorum = _entry(p.body.entries, "quorum")
    assert isinstance(quorum, AutoDuration) and quorum.unit == "days"


def test_attribute_states_and_transitions() -> None:
    r = parse_string(
        "model er\ndef entity order { attributes: [ def attribute state { type: text, "
        "states: [placed, held], transitions: { placed: [held] } } ] }",
        "o.ttrm",
    )
    assert r.errors == ()
    entity = r.definitions[0]
    assert isinstance(entity, EntityDef)
    attr = entity.attributes[0]
    assert attr.states == ("placed", "held")
    assert [(t.from_state, t.to) for t in attr.transitions] == [("placed", ("held",))]


@pytest.mark.parametrize(
    "name",
    [
        "neg-01-toplevel-identity.ttrm",
        "neg-02-named-grant.ttrm",
        "neg-03-transition-no-target.ttrm",
        "neg-04-step-outside-process.ttrm",
    ],
)
def test_parser_rejects(name: str) -> None:
    assert _parse(f"automation-negative/{name}").errors != ()
