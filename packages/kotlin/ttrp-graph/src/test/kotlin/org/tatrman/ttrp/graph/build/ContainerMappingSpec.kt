package org.tatrman.ttrp.graph.build

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.ttrp.graph.GraphFixtures
import org.tatrman.ttrp.graph.model.Branch
import org.tatrman.ttrp.graph.model.Limit
import org.tatrman.ttrp.graph.model.PortDirection
import org.tatrman.ttrp.graph.model.PortRef

/** T2.1.4 — containers: closed functions, port mapping, program wiring, fragment shape. */
class ContainerMappingSpec :
    StringSpec({

        "a decomposed fragment container carries its raw fragment AND its lowered members (P6)" {
            val g = GraphFixtures.buildFixture("hero.ttrp").graph
            val accPrep = g.containers.values.first { it.label == "acc_prep" }
            accPrep.fragment!!.tag shouldBe "sql" // raw interior kept (verbatim emit + C2-f)
            accPrep.memberIds.map { g.nodes.getValue(it)::class.simpleName } shouldBe
                listOf("Load", "Filter", "Project") // clause→node decomposition (C2-a-β)
            accPrep.defaultOut() shouldBe "out" // synthetic default out (port-less fragment)
        }

        "container out/err ports map onto internal node ports" {
            val g = GraphFixtures.buildFixture("hero.ttrp").graph
            val crunch = g.containers.values.first { it.label == "crunch" }
            val branch =
                crunch.memberIds
                    .map { g.nodes.getValue(it) }
                    .filterIsInstance<Branch>()
                    .single()
            crunch.portMapping["result"] shouldBe PortRef(branch.id, "true")
            crunch.declaredPorts.filter { it.direction == PortDirection.IN }.map { it.name } shouldBe listOf("accounts")
        }

        "a chain ending in an OUT port writes the chain's value to that port — the same as `<port> = …`" {
            fun graph(body: String) =
                GraphFixtures
                    .build(
                        "uses world \"acme.worlds.dev\"\n" +
                            "container c(out o) target polars {\n" +
                            "    s = load(files.sales_2026, schema: sales_csv)\n" +
                            "    $body\n" +
                            "}\n",
                    ).graph
            val chained = graph("s -> filter(amount > 0) -> limit(10) -> o")
            val c = chained.containers.values.single { it.label == "c" }
            val limit =
                c.memberIds
                    .map { chained.nodes.getValue(it) }
                    .filterIsInstance<Limit>()
                    .single()
            // the port's producer is the chain's LAST node (it used to fall back to `s`, the unfiltered head)
            c.portMapping["o"] shouldBe PortRef(limit.id, "out")
            limit.label shouldBe "o#1"
            // ...the very graph the assignment form builds
            val assigned = graph("o = s -> filter(amount > 0) -> limit(10)")
            val a = assigned.containers.values.single { it.label == "c" }
            a.portMapping["o"] shouldBe c.portMapping["o"]
            a.memberIds.map { assigned.nodes.getValue(it).label } shouldBe
                c.memberIds.map { chained.nodes.getValue(it).label }
        }

        "program wiring binds default ports (a -> b.port elision)" {
            val g = GraphFixtures.buildFixture("hero.ttrp").graph
            val accPrep = g.containers.values.first { it.label == "acc_prep" }
            val crunch = g.containers.values.first { it.label == "crunch" }
            // acc_prep -> crunch.accounts : from acc_prep's default out to the named in-port.
            val edge = g.edges.single { it.from.nodeId == accPrep.id && it.to.nodeId == crunch.id }
            edge.from.port shouldBe "out"
            edge.to.port shouldBe "accounts"
        }
    })
