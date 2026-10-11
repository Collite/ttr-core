// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.semantics

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Mirrors `packages/semantics/src/__tests__/qname.test.ts` plus the
 * contract §4.1 `Qname` shape (dotted-string value class).
 */
class QnameSpec :
    StringSpec({
        "segments splits on dots" {
            Qname("er.entity.item").segments shouldBe listOf("er", "entity", "item")
        }

        "last is the final segment" {
            Qname("db.dbo.customer").last shouldBe "customer"
        }

        "parent drops the last segment" {
            Qname("er.entity.item").parent shouldBe Qname("er.entity")
        }

        "parent of a single-segment qname is null (root)" {
            Qname("item").parent.shouldBeNull()
        }

        "append adds a segment" {
            Qname("er.entity").append("item") shouldBe Qname("er.entity.item")
        }

        "append onto the empty qname yields the bare segment" {
            Qname("").append("item") shouldBe Qname("item")
        }

        "equality is by value" {
            (Qname("a.b") == Qname("a.b")) shouldBe true
        }

        "toString returns the dotted value" {
            Qname("cnc.role.fact").toString() shouldBe "cnc.role.fact"
        }

        "trailing dot is rejected" {
            shouldThrow<IllegalArgumentException> { Qname("a.b.c.") }
        }

        "empty interior segment is rejected" {
            shouldThrow<IllegalArgumentException> { Qname("a..b") }
        }
    })
