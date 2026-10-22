// SPDX-License-Identifier: Apache-2.0
// ttr-translator — the TTR-P/kantheon translation core (island ↔ RelNode ↔ SQL /
// plan.v1). Extracted whole from kantheon shared/libs/kotlin/query-translator at
// f2e2efb (2026-07-06); root package renamed shared.translator → org.tatrman.translator
// (TR-2). No behavioral change rides the move (TR-7) — package names + build wiring only.

plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `java-test-fixtures` // InMemoryModelHandle ships to consumers via the SPI (contracts §3)
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    // Calcite freezes its default literal charset (ISO-8859-1) at class-load. The
    // library promotes it to UTF-8 at its entry points, but specs touching Calcite
    // directly can load it first in arbitrary order — pin the charset for the test
    // JVM to keep Unicode-literal coverage deterministic (carried from the source lib).
    systemProperty("calcite.default.charset", "UTF-8")
    systemProperty("calcite.default.nationalcharset", "UTF-8")
    systemProperty("calcite.default.collation.name", "UTF-8\$en_US")
}

// calcite-ext custom-parser codegen configurations (decision D7). Declared before `dependencies`
// so they can carry the fmpp / javacc / template-extraction artifacts.
val codegenFmpp: Configuration by configurations.creating
val codegenJavacc: Configuration by configurations.creating
val calciteCodegenTemplates: Configuration by configurations.creating { isTransitive = false }

dependencies {
    api(project(":packages:kotlin:ttr-plan-proto")) // was api(project(":shared:proto")) in kantheon
    api(libs.calcite.core)
    implementation(libs.slf4j.api)
    implementation(libs.protobuf.java.util)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)

    // calcite-ext custom-parser codegen (decision D7) — see the codegen task block below.
    codegenFmpp(libs.fmpp)
    codegenJavacc(libs.javacc)
    calciteCodegenTemplates(libs.calcite.core)

    // InMemoryModelHandle (the ModelHandle SPI test double) ships to consumers via
    // java-test-fixtures (contracts §3). Its public API references plan.v1 types, so
    // re-export the proto on the test-fixtures api classpath.
    testFixturesApi(project(":packages:kotlin:ttr-plan-proto"))
}

// SV-P1 S4 — Maven Central (Central Portal) is the PUBLIC lane (RO-17). vanniktech
// owns the publication (adds the sources + javadoc jars Central requires) and the
// Central target; the GH Packages block below stays as the pre-release staging lane.
mavenPublishing {
    publishToMavenCentral()
    // Central requires signatures and the Central CI lane supplies the key
    // (ORG_GRADLE_PROJECT_signingInMemoryKey). Local builds + the GH Packages
    // staging lane don't sign — gate signAllPublications() on the key's presence
    // so it doesn't hard-fail those (it fails a non-SNAPSHOT version when unkeyed).
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-translator", version.toString())
    pom {
        name.set("TTR Translator")
        description.set(
            "The TTR-P translation core: island ↔ RelNode ↔ SQL / plan.v1 " +
                "(Calcite-backed). Extracted from kantheon query-translator.",
        )
        inceptionYear.set("2025")
        url.set("https://github.com/Collite/ttr-core")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("collite")
                name.set("Collite")
                url.set("https://github.com/Collite")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/Collite/ttr-core.git")
            developerConnection.set("scm:git:git@github.com:Collite/ttr-core.git")
            url.set("https://github.com/Collite/ttr-core")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Collite/ttr-core")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// calcite-ext custom SQL parser generation (fmpp + javacc) — decision D7.
//
// Generates an extended Calcite parser (CalciteExtParserImpl) from the Parser.jj /
// default_config.fmpp templates SHIPPED INSIDE the calcite-core jar (codegen/). Only our
// config.fmpp + includes/parserImpls.ftl are checked in (src/main/codegen/); the Calcite grammar
// itself is extracted at build time so it always tracks the pinned Calcite version (no vendored
// grammar to maintain). Mirrors Calcite's own buildSrc FmppTask / JavaCCTask as plain Gradle tasks
// (we can't depend on Calcite's buildSrc). Ported from ai-platform query-translator (root package
// shared.translator → org.tatrman.translator).
// ---------------------------------------------------------------------------

val parserPackage = "org.tatrman.translator.parser.impl"
val codegenAssembleDir = layout.buildDirectory.dir("calcite-ext-codegen")
val fmppOutDir = layout.buildDirectory.dir("calcite-ext-fmpp")
val javaccOutDir = layout.buildDirectory.dir("generated/calcite-ext-parser")

// Assemble a single codegen dir: Calcite's grammar templates (extracted from the jar) overlaid
// with our config.fmpp + includes/parserImpls.ftl (our overrides win on path collision).
val assembleParserCodegen by tasks.registering(Sync::class) {
    from({ zipTree(calciteCodegenTemplates.singleFile) }) {
        include("codegen/templates/Parser.jj")
        include("codegen/default_config.fmpp")
        include("codegen/includes/compoundIdentifier.ftl")
        eachFile { path = path.removePrefix("codegen/") }
        includeEmptyDirs = false
    }
    from("src/main/codegen")
    into(codegenAssembleDir)
}

// fmpp: render Parser.jj from the templates + our config (the data model layers our config over the
// extracted default_config), exactly as Calcite's FmppTask does.
val generateParserGrammar by tasks.registering {
    dependsOn(assembleParserCodegen)
    inputs.dir(codegenAssembleDir)
    outputs.dir(fmppOutDir)
    // The fmpp Ant task and the `ant` builder are not configuration-cache compatible.
    notCompatibleWithConfigurationCache("uses the fmpp Ant task, like Calcite's own build")
    doLast {
        val configFile = codegenAssembleDir.get().file("config.fmpp").asFile
        val defaultConfigFile = codegenAssembleDir.get().file("default_config.fmpp").asFile
        val templatesDir = codegenAssembleDir.get().dir("templates").asFile

        fun String.tdd() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
        ant.withGroovyBuilder {
            "taskdef"(
                "name" to "fmpp",
                "classname" to "fmpp.tools.AntTask",
                "classpath" to codegenFmpp.asPath,
            )
            "fmpp"(
                "configuration" to configFile,
                "sourceRoot" to templatesDir,
                "outputRoot" to fmppOutDir.get().asFile,
                "data" to "tdd(${configFile.toString().tdd()}), default: tdd(${defaultConfigFile.toString().tdd()})",
            )
        }
    }
}

// javacc: compile the rendered Parser.jj into the parser Java sources.
val generateParser by tasks.registering(JavaExec::class) {
    dependsOn(generateParserGrammar)
    inputs.dir(fmppOutDir)
    outputs.dir(javaccOutDir)
    classpath = codegenJavacc
    mainClass.set("javacc")
    // Reading a Configuration's contents at execution time is not configuration-cache compatible.
    notCompatibleWithConfigurationCache("runs javacc against a resolved Configuration classpath")
    val pkgPath = parserPackage.replace('.', '/')
    doFirst {
        val outRoot = javaccOutDir.get().asFile
        delete(outRoot)
        // fmpp routes Parser.jj into a `javacc/` subdir (Calcite's default_config.fmpp); glob for it.
        val parserJj =
            fileTree(fmppOutDir).matching { include("**/Parser.jj") }.singleFile
        args(
            "-STATIC=false",
            "-LOOKAHEAD:2",
            "-OUTPUT_DIRECTORY:$outRoot/$pkgPath",
            parserJj.absolutePath,
        )
    }
}

sourceSets.named("main") {
    java.srcDir(javaccOutDir)
}

tasks.named("compileKotlin") {
    dependsOn(generateParser)
}

// The generated parser sources land in the main source set (so Kotlin can see CalciteExtParserImpl),
// which makes ktlint's source-scan tasks read that dir — declare the dependency so Gradle doesn't
// flag an implicit one. ktlint only lints .kt/.kts, so the generated .java is ignored anyway.
tasks.matching { it.name.startsWith("runKtlint") }.configureEach {
    dependsOn(generateParser)
}

// The vanniktech `sourcesJar` globs the main source set's srcDirs (which includes the generated
// parser dir), so it must run after generateParser — else the published sources jar races the
// codegen (Gradle flags the implicit dependency and fails the publish). CEP-P3 / architecture R1.
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateParser)
}

// The JavaCC-generated parser sources (CalciteExtParserImpl + support classes) are not doclint-clean
// (raw JavaCC output), and Maven Central requires a javadoc jar. Exclude the generated parser package
// from javadoc and relax doclint so the published javadoc jar builds. CEP-P3 / architecture R1.
tasks.withType<Javadoc>().configureEach {
    exclude("org/tatrman/translator/parser/impl/**")
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
