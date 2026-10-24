// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.bundle

import org.tatrman.ttrp.emit.spi.TtrEmitPlugin
import org.tatrman.ttrp.project.TtrLock
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader

/** A plugin identity/trust violation (contracts §3/§8, `TTRP-LCK` family) — a hard error, never a silent skip. */
class PluginTrustException(
    message: String,
) : RuntimeException(message)

/**
 * The H-6 publisher-signature policy (contracts §8). v1 default is [VERIFY_IF_SIGNED]; [REQUIRE_SIGNED]
 * is the deployment/project knob `[ttrp] require-signed-plugins = true`. Either way a *present* signature
 * MUST verify — the policy only governs what happens when a plugin ships **no** signature at all.
 */
enum class SignaturePolicy {
    /** Unsigned plugins load with a recorded warning; a present signature must still verify. */
    VERIFY_IF_SIGNED,

    /** Unsigned plugins are refused (`TTRP-LCK-012`); a present signature must still verify. */
    REQUIRE_SIGNED,
}

/**
 * PL-P5.S1 — resolves the [TtrEmitPlugin] for an emit target (contracts §8). Two paths:
 *  - [builtin] — a plugin that ships IN-TREE on the compiler classpath (bash is the built-in fallback: a target
 *    with no `ttr.lock [plugins]` entry that ships in-tree), discovered via `java.util.ServiceLoader`.
 *  - [isolated] — a THIRD-PARTY plugin jar, loaded in a parent-last [PluginClassLoader] so the compiler core's
 *    classpath never leaks into the plugin (and the plugin's transitive deps can't collide with the core). Its
 *    identity (`{version, artifact sha256}`) is pinned in `ttr.lock [plugins]` and verified before loading — a
 *    mismatch is a hard `TTRP-LCK` error ([PluginTrustException]).
 *
 * A plugin whose [TtrEmitPlugin.spiVersion] != [TtrEmitPlugin.SPI_VERSION] is rejected — hosting an incompatible
 * SPI is a hard error.
 */
object EmitPluginLoader {
    /**
     * Map the `[ttrp] require-signed-plugins` manifest knob to a [SignaturePolicy]. The policy is only ever
     * *honored* by [isolated] (third-party plugins loaded from a jar); [builtin] in-tree plugins are trusted by
     * construction and ship no publisher signature. Until an [isolated] caller exists (a PL-P5.S2 deferral —
     * see the note at the CLI plugin-resolution site), setting the knob has NO runtime effect, so [onInert] is
     * invoked when it is set — the (currently unenforceable) setting is surfaced, never silently ignored. When
     * [isolated] gains a caller, derive its `policy` argument through THIS function so the knob→policy wire
     * lands atomically with enforcement.
     */
    fun signaturePolicy(
        requireSignedPlugins: Boolean,
        onInert: (String) -> Unit = {},
    ): SignaturePolicy =
        if (requireSignedPlugins) {
            onInert(
                "`[ttrp] require-signed-plugins = true` is recognized but not yet enforceable: isolated " +
                    "third-party emit-plugin loading is a follow-up and in-tree plugins are trusted by " +
                    "construction, so no unsigned plugin can load in this build — the knob is currently inert " +
                    "(it will be honored once isolated loading ships).",
            )
            SignaturePolicy.REQUIRE_SIGNED
        } else {
            SignaturePolicy.VERIFY_IF_SIGNED
        }

    /** Resolve a built-in (classpath) plugin by [targetId]. Throws if none / more than one / SPI-incompatible. */
    fun builtin(targetId: String): TtrEmitPlugin {
        val found = ServiceLoader.load(TtrEmitPlugin::class.java).toList()
        return pick(found, targetId)
    }

    /**
     * Load the third-party plugin [coordinates] (`group:artifact`) from [jar] in an isolated classloader after
     * verifying its artifact sha256 against `ttr.lock [plugins]` ([lock]). Throws [PluginTrustException] when the
     * coordinates are unpinned or the sha256 does not match the pin.
     */
    fun isolated(
        targetId: String,
        coordinates: String,
        jar: Path,
        lock: TtrLock,
        trustedKeyring: Path? = null,
        policy: SignaturePolicy = SignaturePolicy.VERIFY_IF_SIGNED,
        warn: (String) -> Unit = {},
    ): TtrEmitPlugin {
        val jarBytes = Files.readAllBytes(jar)
        // 1. Identity: the artifact sha256 MUST match the `ttr.lock [plugins]` pin (the hard `TTRP-LCK-01x` gate).
        verifyIdentity(jarBytes, coordinates, lock)
        // 2. Trust: apply the H-6 publisher-signature policy to the detached `<jar>.asc` (Maven convention).
        val ascPath = jar.resolveSibling(jar.fileName.toString() + ".asc")
        val ascBytes = if (Files.isRegularFile(ascPath)) Files.readAllBytes(ascPath) else null
        val keyringBytes = trustedKeyring?.takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
        verifySignature(coordinates, jarBytes, ascBytes, keyringBytes, policy, warn)
        val loader = PluginClassLoader(arrayOf(jar.toUri().toURL()), EmitPluginLoader::class.java.classLoader)
        return fromClassLoader(loader, targetId)
    }

    /** Resolve a plugin by [targetId] from an explicit [loader] (the parent-last isolation seam). */
    fun fromClassLoader(
        loader: ClassLoader,
        targetId: String,
    ): TtrEmitPlugin = pick(ServiceLoader.load(TtrEmitPlugin::class.java, loader).toList(), targetId)

    /**
     * The `ttr.lock [plugins]` trust gate (H-6, contracts §3/§8): [coordinates] MUST be pinned and [jarBytes]'
     * sha256 MUST equal the pin. Extracted as a pure function so the trust check is unit-testable without a real
     * plugin jar.
     */
    fun verifyIdentity(
        jarBytes: ByteArray,
        coordinates: String,
        lock: TtrLock,
    ) {
        val entry =
            lock.plugins[coordinates]
                ?: throw PluginTrustException(
                    "TTRP-LCK-010: emit plugin '$coordinates' is not pinned in ttr.lock [plugins] — add its " +
                        "{ version, sha256 } entry (plugin identity is canon, BQ-4).",
                )
        val hex = MessageDigest.getInstance("SHA-256").digest(jarBytes).joinToString("") { "%02x".format(it) }
        val actual = "sha256:$hex"
        val expected = if (entry.sha256.startsWith("sha256:")) entry.sha256 else "sha256:${entry.sha256}"
        // Hex is case-insensitive: a lock entry written with uppercase digits must still match (fail-closed
        // usability — a mismatch is a hard refusal, so a spurious case difference must not manufacture one).
        if (!actual.equals(expected, ignoreCase = true)) {
            throw PluginTrustException(
                "TTRP-LCK-011: emit plugin '$coordinates' artifact sha256 $actual does not match the ttr.lock pin " +
                    "$expected — the pinned artifact was tampered with or the lock is stale.",
            )
        }
    }

    /**
     * The H-6 publisher-signature gate (contracts §8), extracted as a pure function so the policy is
     * unit-testable without a jar/keyring on disk. Given the artifact bytes, the detached-signature bytes
     * ([ascBytes] — null iff no `<jar>.asc` sibling), the trusted publisher keyring ([keyringBytes] — null
     * iff none configured) and the [policy]:
     *  - **unsigned** (`ascBytes == null`): [SignaturePolicy.REQUIRE_SIGNED] refuses (`TTRP-LCK-012`, names the
     *    knob); [SignaturePolicy.VERIFY_IF_SIGNED] loads after recording a [warn] advisory.
     *  - **signed** (`ascBytes != null`): a present signature MUST verify **regardless of policy** — a missing
     *    trusted keyring (`TTRP-LCK-014`) or a failed verification (`TTRP-LCK-013`, the tampered-jar case) is
     *    always a hard refusal.
     */
    fun verifySignature(
        coordinates: String,
        jarBytes: ByteArray,
        ascBytes: ByteArray?,
        keyringBytes: ByteArray?,
        policy: SignaturePolicy,
        warn: (String) -> Unit = {},
    ) {
        if (ascBytes == null) {
            if (policy == SignaturePolicy.REQUIRE_SIGNED) {
                throw PluginTrustException(
                    "TTRP-LCK-012: emit plugin '$coordinates' is unsigned (no '<jar>.asc' beside the artifact) but " +
                        "`[ttrp] require-signed-plugins = true` — install the publisher signature or relax the knob.",
                )
            }
            warn(
                "[ttrp] emit plugin '$coordinates' is unsigned — loaded under verify-if-signed; set " +
                    "`[ttrp] require-signed-plugins = true` to refuse unsigned plugins (H-6).",
            )
            return
        }
        // A present signature must verify no matter the policy — an unverifiable/bad sig is never silently loaded.
        if (keyringBytes == null) {
            throw PluginTrustException(
                "TTRP-LCK-014: emit plugin '$coordinates' ships a signature but no trusted publisher keyring is " +
                    "configured — trust cannot be established; configure the keyring or remove the signature.",
            )
        }
        if (!PluginSignature.verifyDetached(jarBytes, ascBytes, keyringBytes)) {
            throw PluginTrustException(
                "TTRP-LCK-013: emit plugin '$coordinates' signature verification FAILED — the artifact was tampered " +
                    "with or is signed by a key absent from the trusted keyring (refused regardless of policy).",
            )
        }
    }

    private fun pick(
        found: List<TtrEmitPlugin>,
        targetId: String,
    ): TtrEmitPlugin {
        val matches = found.filter { it.targetId == targetId }
        val plugin =
            when {
                matches.isEmpty() -> {
                    val targets = found.map { it.targetId }.sorted()
                    error("no emit plugin for target '$targetId' (found targets: $targets)")
                }
                matches.size > 1 -> error("more than one emit plugin for target '$targetId' — ambiguous classpath")
                else -> matches.single()
            }
        require(plugin.spiVersion in TtrEmitPlugin.SUPPORTED_VERSIONS) {
            "emit plugin '$targetId' was built against SPI v${plugin.spiVersion}, host accepts ${TtrEmitPlugin.SUPPORTED_VERSIONS}"
        }
        return plugin
    }
}

/**
 * A parent-LAST classloader for a third-party emit plugin (contracts §8): the plugin's own jar is searched first,
 * so its transitive dependencies cannot collide with the compiler core's. Only the SPI package and the platform
 * runtime (`java.`/`kotlin.`/…) delegate to the parent — so the returned plugin instance is assignable to
 * [TtrEmitPlugin] here, but the compiler core's OWN classes are invisible to the plugin (they are neither shared
 * nor in the plugin jar ⇒ `ClassNotFoundException`). That is the "no core classpath leak" guarantee, made physical.
 */
class PluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
) : URLClassLoader(urls, parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            // SPI contract + platform runtime are SHARED with the parent (identity must match across the boundary).
            if (isShared(name)) return super.loadClass(name, resolve)
            // Everything else is child-only: found in the plugin jar or not at all (the core never leaks in).
            val c = findClass(name)
            if (resolve) resolveClass(c)
            return c
        }
    }

    private fun isShared(name: String): Boolean =
        name.startsWith("org.tatrman.ttrp.emit.spi.") ||
            name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("kotlinx.") ||
            name.startsWith("org.jetbrains.annotations.")
}
