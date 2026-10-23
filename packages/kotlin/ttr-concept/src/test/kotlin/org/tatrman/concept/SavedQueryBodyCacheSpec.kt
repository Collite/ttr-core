// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.SavedQueryBody
import java.util.concurrent.CancellationException

class SavedQueryBodyCacheSpec :
    StringSpec({
        val query = QualifiedName.getDefaultInstance()
        val body = SavedQueryBody(PlanNode.getDefaultInstance(), emptyList(), emptyList())
        for (failure in listOf(
            OutOfMemoryError("transient"),
            InterruptedException("interrupted"),
            CancellationException("cancelled"),
            UnsupportedOperationException("unexpected"),
        )) {
            "${failure.javaClass.simpleName} is rethrown and the same query can be retried" {
                var calls = 0
                val cache = SavedQueryBodyCache { if (++calls == 1) throw failure else body }
                (runCatching { cache.get(query) }.exceptionOrNull() === failure) shouldBe true
                (cache.get(query) === body) shouldBe true
                (cache.get(query) === body) shouldBe true
                calls shouldBe 2
            }
        }
        for (failure in listOf(IllegalArgumentException("invalid model"), IllegalStateException("invalid query"))) {
            "${failure.javaClass.simpleName} from the model is cached" {
                var calls = 0
                val cache =
                    SavedQueryBodyCache {
                        calls++
                        throw failure
                    }
                repeat(2) { (runCatching { cache.get(query) }.exceptionOrNull() === failure) shouldBe true }
                calls shouldBe 1
            }
        }
        for (code in listOf("parse_exception", "rel_conversion_failed", "validation_failed")) {
            "translator $code failures are retryable after their exception type is lost" {
                var calls = 0
                val cache =
                    SavedQueryBodyCache {
                        if (++calls ==
                            1
                        ) {
                            throw queryBodyFailure(query, code, "unknown cause")
                        } else {
                            body
                        }
                    }
                runCatching { cache.get(query) }.isFailure shouldBe true
                (cache.get(query) === body) shouldBe true
                calls shouldBe 2
            }
        }
        "translator syntax failures remain cached" {
            var calls = 0
            val cache =
                SavedQueryBodyCache {
                    calls++
                    throw queryBodyFailure(query, "parse_failed", "bad SQL")
                }
            val first = runCatching { cache.get(query) }.exceptionOrNull()
            (runCatching { cache.get(query) }.exceptionOrNull() === first) shouldBe true
            calls shouldBe 1
        }
    })
