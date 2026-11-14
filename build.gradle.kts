// SPDX-License-Identifier: Apache-2.0
// Root build for the modeler-kotlin Gradle build. This coexists with the pnpm
// TypeScript workspace: the two builds share no artifacts, but both read the
// canonical grammar at packages/grammar/src/TTR.g4. See docs/grammar-master/.

plugins {
    // MW S3.7 T3 — `base` gives the root a `check` lifecycle, so `./gradlew build` (PR CI) runs the bundle gate below.
    base
    // SV-P1 S4 — declare vanniktech at the root with `apply false` so the shared
    // `MavenCentralBuildService` registers once for the whole build. Without this,
    // configuring more than one Central-publishing module together (e.g. a
    // repo-wide `publishToMavenCentral`) fails to create `prepareMavenCentralPublishing`.
    // Kotlin is declared here `apply false` too because vanniktech reads the Kotlin
    // plugin classes to detect the version and requires them on the root classpath
    // (per the plugin's own error message). Each module applies both for real; the
    // root only puts them on the classpath.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish.vanniktech) apply false
}

// AG-14 (2026-10-02) — ttrp-* publish in the `grammar` bundle, but `ttrp-emit` `api`s ttr-translator, which
// releases on its OWN line (`translator/v*`). Gradle writes a project dependency's own `version` into the POM,
// so a grammar publish would otherwise name `ttr-translator:<grammar version>` — a version nothing cut (the
// 0.10.3 lockstep trap). `-PtranslatorVersion=<x.y.z>` (publish.yml passes the latest translator tag on
// grammar tags only, after checking the translator sources still equal it) gives the two translator modules
// their PUBLISHED version for that build, so the POMs name what consumers can resolve.
val translatorModules = setOf("ttr-translator", "ttr-plan-proto")
val pinnedTranslatorVersion = findProperty("translatorVersion") as String?

allprojects {
    group = "org.tatrman"
    version =
        if (pinnedTranslatorVersion != null && name in translatorModules) {
            pinnedTranslatorVersion
        } else {
            (findProperty("version") as String?).takeUnless { it == "unspecified" } ?: "0.0.1-LOCAL"
        }
}

// MW S3.7 T3 (from S3.4 review-260 F1, caught only by review: ttr-profile was missing from the bundle) — the grammar
// bundle's release closure. publish.yml's `grammar/v*` MODULES must list every project the published modules depend on
// at runtime (api · implementation · runtimeOnly, transitively): a POM names its project dependencies at the bundle's
// version, so a project left out is a version nothing publishes. The translator modules ride their own pinned line
// (-PtranslatorVersion) and are the only allowed exceptions. Red on `./gradlew build`.
val grammarBundleClosure by tasks.registering {
    group = "verification"
    description = "publish.yml's grammar MODULES covers the runtime project-dependency closure of every module it lists"
    val workflow = layout.projectDirectory.file(".github/workflows/publish.yml")
    inputs.file(workflow)
    doLast {
        val line =
            workflow.asFile
                .readLines()
                .map { it.trim() }
                .single { it.startsWith("MODULES=") && ":packages:kotlin:ttr-metadata-cli:publish" in it }
        val listed =
            Regex(""":packages:kotlin:[A-Za-z0-9-]+(?=:publish)""").findAll(line).map { it.value }.toSet()
        check(listed.isNotEmpty()) { "no grammar MODULES line in ${workflow.asFile}" }
        val runtime = listOf("api", "implementation", "runtimeOnly")

        fun direct(path: String): Set<String> =
            runtime
                .mapNotNull { project(path).configurations.findByName(it) }
                .flatMap { it.dependencies.withType(ProjectDependency::class.java).map { dep -> dep.path } }
                .toSet()
        val closure = mutableSetOf<String>()
        val queue = ArrayDeque(listed)
        while (queue.isNotEmpty()) {
            direct(queue.removeFirst()).filter { closure.add(it) }.forEach(queue::addLast)
        }
        val allowed = translatorModules.map { ":packages:kotlin:$it" }.toSet()
        val missing = (closure - listed - allowed).sorted()
        check(missing.isEmpty()) {
            "publish.yml grammar MODULES lacks ${missing.joinToString()} — a published POM names them at the bundle version"
        }
        logger.lifecycle("grammar bundle closure OK: ${listed.size} modules, closure ${closure.size}")
    }
}
tasks.named("check") { dependsOn(grammarBundleClosure) }
