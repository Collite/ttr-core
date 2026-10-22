// SPDX-License-Identifier: Apache-2.0
package org.tatrman.concept

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.toPath

/**
 * CN-P3 S3.8 T1/T2 (architecture §7, the S6 rule "a pure function of resolved, cached inputs") — made mechanical over
 * the constant pools of the classes `ttr-concept` compiles, so an import, a fully qualified name and a descriptor are
 * all seen:
 * - **never a write** (S3.5 T5, A-CN-50; contracts §2.3): no JDBC (`java.sql`, `javax.sql`) and no translator DML
 *   (`StoreNode`, `StoreDmlUnparser`) — anywhere in main;
 * - **no I/O, no environment** (T2): no `java.net`, no file I/O (`java.io.File*`, `java.nio.file`, channels, Kotlin's
 *   file extensions), no classpath resource read, no `System.getenv` / `getProperty`, no process — outside `cli/` (the
 *   `ttr cn` command reads the model root it is handed);
 * - **no wall clock, no randomness** (T1): no argument-less `now()` on a `java.time` type, no `currentTimeMillis` /
 *   `nanoTime`, no system clock but `CnConceptService`'s one default (`clock: Clock = Clock.systemUTC()` — the
 *   injection point), no `Random` / `randomUUID`, no `identityHashCode`.
 *
 * Every rule is proven able to fire: on the test classes (the live suite's JDBC and `getenv`), on `cli/` (file I/O)
 * and on a JDK class (`LocalDate.now()` reads `Clock.systemDefaultZone()`).
 */
class PuritySpec :
    FunSpec({
        fun classes(root: Path): List<Path> =
            Files.walk(root).use { s -> s.filter { it.isRegularFile() && it.extension == "class" }.sorted().toList() }

        fun scan(
            root: Path,
            rule: Rule,
            skip: (ClassRefs) -> Boolean = { false },
        ): List<String> =
            classes(root).map { ClassRefs.read(Files.readAllBytes(it)) }.filterNot(skip).flatMap(rule::hits)

        fun root(c: Class<*>): Path =
            c.protectionDomain.codeSource.location
                .toURI()
                .toPath()

        val main = root(ConceptService::class.java)
        val tests = root(PuritySpec::class.java)
        val cli: (ClassRefs) -> Boolean = { it.name.startsWith("org/tatrman/concept/cli/") }

        test("the main classes reference no JDBC and no DML") {
            classes(main).size shouldBeGreaterThan 20
            scan(main, WRITES).shouldBeEmpty()
        }

        test("outside cli/, the main classes do no I/O and read no environment") {
            scan(main, IO, skip = cli).shouldBeEmpty()
        }

        test("the main classes read no wall clock and no randomness — the clock is injected") {
            scan(main, CLOCK).shouldBeEmpty()
        }

        test("each rule fires where its reference is") {
            // the live suite's JDBC (this spec's own classes left out: they hold the forbidden names as strings)
            val self: (ClassRefs) -> Boolean = { it.name.startsWith("org/tatrman/concept/PuritySpec") }
            scan(tests, WRITES, skip = self).filter { "Live" in it }.shouldNotBeEmpty()
            scan(tests, IO).filter { "System.getenv" in it }.shouldNotBeEmpty() // LivePg reads TTR_CONN_ERP_PG
            scan(main, IO, skip = { !cli(it) }).shouldNotBeEmpty() // `ttr cn` reads the model root
            val jdk = LocalDate::class.java.getResourceAsStream("LocalDate.class")!!.use { it.readBytes() }
            CLOCK.hits(ClassRefs.read(jdk)).shouldNotBeEmpty() // LocalDate.now() → Clock.systemDefaultZone()
        }
    }) {
    /** A purity rule: forbidden name fragments anywhere in a constant pool, and forbidden member references. */
    class Rule(
        private val fragments: List<String>,
        private val members: List<Regex> = emptyList(),
        private val allowed: Set<String> = emptySet(),
    ) {
        fun hits(c: ClassRefs): List<String> =
            (
                fragments.filter { f -> c.strings.any { f in it } } +
                    c.members.filter { m -> members.any { it.containsMatchIn(m) } }
            ).filterNot { "${c.name} $it" in allowed }
                .map { "${c.name} → $it" }
    }

    /** One class file's constant pool: its own name, every string in the pool, and each member it references. */
    class ClassRefs(
        val name: String,
        val strings: List<String>,
        /** `owner/Name.member:descriptor`, `owner` `/`-separated as the pool writes it. */
        val members: List<String>,
    ) {
        companion object {
            fun read(bytes: ByteArray): ClassRefs {
                val d = DataInputStream(ByteArrayInputStream(bytes))
                require(d.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
                d.readUnsignedShort() // minor
                d.readUnsignedShort() // major
                val n = d.readUnsignedShort()
                val utf8 = arrayOfNulls<String>(n)
                val ref = IntArray(n)
                val pair = arrayOfNulls<IntArray>(n)
                val tag = IntArray(n)
                var i = 1
                while (i < n) {
                    tag[i] = d.readUnsignedByte()
                    when (tag[i]) {
                        1 -> utf8[i] = d.readUTF()
                        3, 4 -> d.readInt()
                        5, 6 -> d.readLong().also { i++ } // a long or double takes two slots
                        7, 8, 16, 19, 20 -> ref[i] = d.readUnsignedShort()
                        9, 10, 11, 12, 17, 18 -> pair[i] = intArrayOf(d.readUnsignedShort(), d.readUnsignedShort())
                        15 -> d.readUnsignedByte().also { d.readUnsignedShort() }
                        else -> error("constant pool tag ${tag[i]} at $i")
                    }
                    i++
                }
                d.readUnsignedShort() // access flags
                val self = utf8[ref[d.readUnsignedShort()]]!!
                val members =
                    (1 until n).filter { tag[it] in 9..11 }.map { m ->
                        val (owner, nat) = pair[m]!!
                        val (name, desc) = pair[nat]!!
                        "${utf8[ref[owner]]}.${utf8[name]}:${utf8[desc]}"
                    }
                return ClassRefs(self, utf8.filterNotNull(), members)
            }
        }
    }

    companion object {
        val WRITES =
            Rule(listOf("java/sql/", "javax/sql/", "org/tatrman/plan/v1/StoreNode", "StoreDmlUnparser"))

        val IO =
            Rule(
                listOf(
                    "java/net/",
                    "java/nio/file/",
                    "java/nio/channels/",
                    "java/io/File",
                    "java/io/RandomAccessFile",
                    "kotlin/io/FilesKt",
                    "kotlin/io/path/",
                    "java/lang/ProcessBuilder",
                ),
                listOf(
                    Regex("^java/lang/System\\.(getenv|getProperty|getProperties):"),
                    Regex("^java/lang/(Class|ClassLoader)\\.(getResource|getSystemResource)\\w*:"),
                    Regex("^java/lang/Runtime\\.exec:"),
                ),
            )

        val CLOCK =
            Rule(
                listOf("java/util/Random", "java/security/SecureRandom", "kotlin/random/", "ThreadLocalRandom"),
                listOf(
                    Regex("^java/time/\\w+\\.now:\\(\\)"),
                    Regex("^java/lang/System\\.(currentTimeMillis|nanoTime):"),
                    Regex("^java/time/(Clock|InstantSource)\\.system\\w*:"),
                    Regex("^java/util/UUID\\.randomUUID:"),
                    Regex("^java/lang/Math\\.random:"),
                    Regex("^java/lang/System\\.identityHashCode:"), // differs run to run
                ),
                allowed = setOf("org/tatrman/concept/CnConceptService java/time/Clock.systemUTC:()Ljava/time/Clock;"),
            )
    }
}
