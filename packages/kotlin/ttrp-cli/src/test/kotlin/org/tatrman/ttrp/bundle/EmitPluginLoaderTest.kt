// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.ttrp.emit.spi.EmitRequest
import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.project.LockPlugin
import org.tatrman.ttrp.project.LockWorld
import org.tatrman.ttrp.project.TtrLock
import java.security.MessageDigest

/**
 * PL-P5.S1.T5/T6 — emit-plugin loading + isolation (contracts §8, H-6). The built-in bash plugin resolves in-tree
 * (the fallback); a third-party plugin's identity is pinned in `ttr.lock [plugins]` (TTRP-LCK on mismatch); the
 * isolated classloader shares only the SPI and hides the compiler core (no core-classpath leak); and `emit` takes
 * only an [EmitRequest] — a plugin has no ambient env/filesystem handles (enforced by the API shape).
 */
class EmitPluginLoaderTest :
    StringSpec({

        fun lock(vararg entries: Pair<String, LockPlugin>) =
            TtrLock(world = LockWorld("w", "sha256:" + "0".repeat(64)), plugins = entries.toMap())

        fun sha(bytes: ByteArray) =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        "builtin resolves the in-tree bash plugin (the fallback path)" {
            val p = EmitPluginLoader.builtin("bash")
            p.targetId shouldBe "bash"
            p.spiVersion shouldBe TtrEmitPlugin.SPI_VERSION
        }

        "a Java SPI v1 plugin needs no v2 methods and the host accepts it" {
            val plugin = EmitPluginLoader.builtin("legacy-v1")
            plugin.spiVersion shouldBe 1
            plugin.supportsRecipes shouldBe false
        }

        "the v2 host still refuses an unsupported future SPI major" {
            shouldThrow<IllegalArgumentException> { EmitPluginLoader.builtin("future-spi") }.message!! shouldContain
                "SPI v99"
        }

        "builtin throws for an unknown target" {
            shouldThrow<IllegalStateException> { EmitPluginLoader.builtin("no-such-target") }
        }

        "verifyIdentity: an unpinned plugin is refused (TTRP-LCK-010)" {
            val ex =
                shouldThrow<PluginTrustException> {
                    EmitPluginLoader.verifyIdentity("jar".toByteArray(), "org.tatrman:ttr-emit-x", lock())
                }
            ex.message!! shouldContain "TTRP-LCK-010"
        }

        "verifyIdentity: a sha256 mismatch is refused (TTRP-LCK-011)" {
            val l = lock("org.tatrman:ttr-emit-x" to LockPlugin("1.0.0", "sha256:" + "0".repeat(64)))
            val ex =
                shouldThrow<PluginTrustException> {
                    EmitPluginLoader.verifyIdentity("jar".toByteArray(), "org.tatrman:ttr-emit-x", l)
                }
            ex.message!! shouldContain "TTRP-LCK-011"
        }

        "verifyIdentity: a matching sha256 passes (with and without the sha256: prefix in the lock)" {
            val bytes = "jar".toByteArray()
            EmitPluginLoader.verifyIdentity(bytes, "x:y", lock("x:y" to LockPlugin("1.0.0", sha(bytes))))
            // the pin may be recorded without the algorithm prefix — still matches.
            val unprefixed = sha(bytes).removePrefix("sha256:")
            EmitPluginLoader.verifyIdentity(bytes, "x:y", lock("x:y" to LockPlugin("1.0.0", unprefixed)))
        }

        "the isolated classloader shares the SPI but hides the compiler core (no leak)" {
            val cl = PluginClassLoader(emptyArray(), EmitPluginLoaderTest::class.java.classLoader)
            // the SPI contract is shared — same Class instance across the boundary (so instances are assignable).
            cl.loadClass("org.tatrman.ttrp.emit.spi.TtrEmitPlugin") shouldBe TtrEmitPlugin::class.java
            // a compiler-core class is invisible through the isolated loader (empty plugin urls) ⇒ no leak.
            shouldThrow<ClassNotFoundException> { cl.loadClass("org.tatrman.ttrp.bundle.BundleAssembler") }
        }

        "emit has no ambient handles — EmitRequest is the only input (API shape)" {
            val emit = TtrEmitPlugin::class.java.methods.single { it.name == "emit" }
            emit.parameterTypes.toList() shouldBe listOf(EmitRequest::class.java)
        }
    })
