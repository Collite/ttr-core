# SPDX-License-Identifier: Apache-2.0
"""Smoke test: the generated Python message classes import at their canonical
`org.tatrman.*` module paths and round-trip through protobuf serialization."""

from __future__ import annotations


def test_plannode_roundtrip() -> None:
    from org.tatrman.plan.v1 import plan_pb2

    node = plan_pb2.PlanNode()
    node.scan.object.name = "customer"
    node.scan.object.namespace = "dbo"
    node.scan.object.schema_code = plan_pb2.SchemaCode.DB

    parsed = plan_pb2.PlanNode.FromString(node.SerializeToString())
    assert parsed == node


def test_pipeline_context_roundtrip() -> None:
    from org.tatrman.plan.v1 import context_pb2

    ctx = context_pb2.PipelineContext(correlation_id="corr-1", user_id="u-1")
    ctx.auth_roles.append("analyst")

    parsed = context_pb2.PipelineContext.FromString(ctx.SerializeToString())
    assert parsed == ctx


def test_dfdsl_pipeline_roundtrip() -> None:
    from org.tatrman.dfdsl.v1 import dfdsl_pb2

    pipeline = dfdsl_pb2.Pipeline()
    pipeline.ops.add().select.columns.add().name = "id"
    pipeline.ops.add().limit.n = 10

    parsed = dfdsl_pb2.Pipeline.FromString(pipeline.SerializeToString())
    assert parsed == pipeline


def test_project_expanded_from_roundtrip() -> None:
    """LQ (L-11) - `ProjectNode.expanded_from`, the boundary stamp of an inlined logical query."""
    from org.tatrman.plan.v1 import plan_pb2

    node = plan_pb2.PlanNode()
    node.project.input.scan.object.name = "sale"
    node.project.expanded_from.schema_code = plan_pb2.SchemaCode.ER
    node.project.expanded_from.namespace = "entity"
    node.project.expanded_from.name = "shopper_revenue"

    parsed = plan_pb2.PlanNode.FromString(node.SerializeToString())
    assert parsed == node
    assert parsed.project.HasField("expanded_from")
    assert not plan_pb2.ProjectNode().HasField("expanded_from")


def test_join_condition_required_roundtrip() -> None:
    """ttr-server#232 - `JoinNode.condition_required`, the mark of a bare JOIN the model could not condition."""
    from org.tatrman.plan.v1 import plan_pb2

    node = plan_pb2.PlanNode()
    node.join.left.scan.object.name = "calendar"
    node.join.right.scan.object.name = "product"
    node.join.join_type = plan_pb2.JoinType.INNER
    node.join.condition_required = True

    parsed = plan_pb2.PlanNode.FromString(node.SerializeToString())
    assert parsed == node
    assert parsed.join.condition_required
    assert not plan_pb2.JoinNode().condition_required


def test_cn_frames_roundtrip() -> None:
    """CN-P3 S3.1 - the frozen cn frames import and round-trip (refs, `statement.v1`, `assertion.v1`)."""
    from org.tatrman.assertion.v1 import assertion_pb2
    from org.tatrman.concept.v1 import refs_pb2
    from org.tatrman.statement.v1 import statement_pb2

    s = statement_pb2.Statement(ask=statement_pb2.ASK_VALUE, locale="cs")
    s.measures.add().qname = "cn.revenue"
    s.time.period_code = "2026-01"
    s.as_of.CopyFrom(refs_pb2.Instant(value="2026-10-22", granularity=refs_pb2.DAY))
    assert statement_pb2.Statement.FromString(s.SerializeToString()) == s

    a = assertion_pb2.Assertion()
    a.event.qname = "cn.Transfer"
    a.identity.new = True
    p = a.participants.add(label="from")
    p.member.resolved_value = "501"
    assert assertion_pb2.Assertion.FromString(a.SerializeToString()) == a


def test_cn_frames_canonical_json_matches_the_kotlin_samples() -> None:
    """The JSON samples ttr-concept prints canonically (Kotlin JsonFormat) parse in Python to the same message."""
    import json
    from pathlib import Path

    from google.protobuf import json_format
    from org.tatrman.assertion.v1 import assertion_pb2
    from org.tatrman.statement.v1 import statement_pb2

    frames = (
        Path(__file__).resolve().parents[3]
        / "kotlin"
        / "ttr-concept"
        / "src"
        / "test"
        / "resources"
        / "frames"
    )
    samples = sorted(frames.glob("*.json"))
    assert samples, frames
    for f in samples:
        message = (
            statement_pb2.Statement()
            if f.name.startswith("statement")
            else assertion_pb2.Assertion()
        )
        json_format.Parse(f.read_text(), message)
        assert json_format.MessageToDict(message) == json.loads(f.read_text()), f.name
