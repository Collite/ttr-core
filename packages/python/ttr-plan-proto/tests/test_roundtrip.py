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
