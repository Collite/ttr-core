// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.metadata.source

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LayoutFileRefCompatibilitySpec :
    StringSpec({
        val oldTypes =
            arrayOf(
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                LayoutReadFailure::class.java,
            )
        "the 0.22.10 seven-argument JVM constructor still constructs a TTR graph ref" {
            val constructor = LayoutFileRef::class.java.getConstructor(*oldTypes)
            val failure = LayoutReadFailure("a.ttrl", "fixture")
            val ref = constructor.newInstance("/a.ttrg", "a.ttrg", "graph a {}", "/a.ttrl", "ttrl 1", "a.ttrl", failure)
            ref.format shouldBe LayoutFileFormat.TTRG
            ref.sidecarPath shouldBe "/a.ttrl"
            ref.sidecarContent shouldBe "ttrl 1"
            ref.sidecarSource shouldBe "a.ttrl"
            ref.unreadable shouldBe failure
        }
        "the 0.22.10 default-argument JVM constructor retains the old defaults" {
            val constructor =
                LayoutFileRef::class.java.getConstructor(
                    *oldTypes,
                    Int::class.javaPrimitiveType,
                    Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
                )
            val ref = constructor.newInstance("/a.ttrg", "a.ttrg", "graph a {}", null, null, null, null, 120, null)
            ref.format shouldBe LayoutFileFormat.TTRG
            ref.path shouldBe "/a.ttrg"
            ref.source shouldBe "a.ttrg"
            ref.content shouldBe "graph a {}"
            ref.sidecarPath shouldBe null
            ref.sidecarContent shouldBe null
            ref.sidecarSource shouldBe null
            ref.unreadable shouldBe null
        }
    })
