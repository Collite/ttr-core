// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.translator.framework.SavedQueryBody
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Snapshot-local results; transient failures must never poison a long-lived model handle. */
internal class SavedQueryBodyCache(
    private val compile: (QualifiedName) -> SavedQueryBody,
) {
    private val results = ConcurrentHashMap<QualifiedName, Result<SavedQueryBody>>()
    private val compiling = ThreadLocal.withInitial { mutableSetOf<QualifiedName>() }

    fun get(query: QualifiedName): SavedQueryBody {
        results[query]?.let { return it.getOrThrow() }
        val visiting = compiling.get()
        check(visiting.add(query)) { "Cyclic ER query body: $query" }
        val result =
            try {
                try {
                    Result.success(compile(query))
                } catch (failure: Exception) {
                    // CancellationException is an IllegalStateException, but is not a model error.
                    if (failure is CancellationException ||
                        (failure !is IllegalArgumentException && failure !is IllegalStateException)
                    ) {
                        throw failure
                    }
                    Result.failure(failure)
                }
            } finally {
                visiting.remove(query)
                if (visiting.isEmpty()) compiling.remove()
            }
        return (results.putIfAbsent(query, result) ?: result).getOrThrow()
    }
}

/** Translator failures lose their original exception: cache syntax errors, retry broader engine failures. */
internal fun queryBodyFailure(
    query: QualifiedName,
    code: String,
    message: String,
): RuntimeException {
    val text = "Cannot compile ER query $query: $code: $message"
    return if (code == "parse_failed") IllegalArgumentException(text) else RuntimeException(text)
}
