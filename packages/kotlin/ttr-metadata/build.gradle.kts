// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `java-test-fixtures` // M2 — shared fixture home for ttrp-frontend (contracts §8)
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
    inputs.file("../../grammar/src/generated/property-map.ts")
    // VL-P0: LayoutResolverSpec reads the shared layout vectors in place (never a copy).
    inputs.dir("../../package-sdk/schemas/layout-vectors")
    // CN-P2 S2.5: the cn specs load conformance fixtures 75–84 in place.
    inputs.dir("../../../tests/conformance/fixtures")
    inputs.dir("../../../tests/conformance/md-hop")
}

dependencies {
    // Re-exported: consumers get one coherent ttr-* set (contracts §1).
    api(project(":packages:kotlin:ttr-parser"))
    implementation(libs.kotlinx.ser.json) // Deterministic md-hop report rendering.
    api(project(":packages:kotlin:ttr-writer"))
    api(project(":packages:kotlin:ttr-semantics"))
    // S6-A — the MemberCatalog capability implements ttr-md-resolver's member interfaces
    // (MemberCatalog/MemberSnapshot/MemberIndex live there, the consumer-facing contract, MDS1);
    // metadata → resolver is the one-way arrow (resolver never depends back). api, matching the
    // "one coherent ttr-* set" convention above.
    api(project(":packages:kotlin:ttr-md-resolver"))
    implementation(libs.snakeyaml) // XM authored rename sidecars; safe data-only constructor.
    implementation(libs.jgrapht.core)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core) // MetadataRefresher (M1.2)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.jena.arq) {
        // Turtle/SPARQL only; binary RDF Protobuf is outside the test oracle and the MD3 classpath.
        exclude(group = "com.google.protobuf")
    }
    // EN-P1.2 — parse the checked-in writability.schema.json fixture for the anti-drift parity spec.
    testImplementation(libs.kotlinx.ser.json)
    testImplementation(libs.tomlj) // Fixture caller supplies public connection descriptors (XM C-22).
    testImplementation(libs.h2) // S6-A — DbMemberSource unit test (in-memory JDBC, test-only)

    // Fixture helper code (MetadataFixtures) references library + parser types.
    testFixturesApi(project(":packages:kotlin:ttr-parser"))
    testFixturesImplementation(libs.slf4j.api)
}

// MD3 / architecture §2.1: the core stays off heavy classpaths. Gate, not convention.
val bannedDependencyGroups =
    setOf(
        "org.apache.jena",
        "io.ktor",
        "io.grpc",
        "io.opentelemetry",
        "org.eclipse.jgit",
        // commons-compress rides the -git artifact only.
        "org.apache.commons",
        "com.google.protobuf",
    )
val dependencyRules =
    tasks.register("dependencyRules") {
        val runtime = configurations.runtimeClasspath
        doLast {
            val offenders =
                runtime
                    .get()
                    .resolvedConfiguration.resolvedArtifacts
                    .map { it.moduleVersion.id.group }
                    .filter { g -> bannedDependencyGroups.any { g == it || g.startsWith("$it.") } }
                    .distinct()
            check(offenders.isEmpty()) {
                "Banned groups on ttr-metadata runtimeClasspath: $offenders (MD3 / architecture §2.1)"
            }
        }
    }
tasks.named("check") { dependsOn(dependencyRules) }

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
    coordinates("org.tatrman", "ttr-metadata", version.toString())
    pom {
        name.set("TTR Metadata")
        description.set("TTR model graph, queries and world resolution (extracted from the kantheon metadata service).")
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

// XM C-13: installable MDG artifact generated by the same renderer as the library.
val renderTtrMdg by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Render the Tatrman TTR MDG Technology for Enterprise Architect"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.tatrman.ttr.metadata.export.xmi.RenderTtrMdg")
    val output = layout.buildDirectory.file("distributions/ttr-mdg.xml")
    outputs.file(output)
    args(output.get().asFile.absolutePath)
}

// XM: the export grammar header follows the canonical marker, never a hardcoded release pin.
val exportGrammarResources by tasks.registering {
    val grammar = file("../../grammar/src/TTR.g4")
    val out = layout.buildDirectory.dir("generated/export-grammar")
    inputs.file(grammar)
    outputs.dir(out)
    doLast {
        val version = Regex("@grammar-version:\\s*(\\S+)").find(grammar.readText())!!.groupValues[1]
        val target = out.get().file("org/tatrman/ttr/metadata/export/grammar.properties").asFile
        target.parentFile.mkdirs()
        target.writeText("grammar=$version\n")
    }
}
sourceSets.main { resources.srcDir(exportGrammarResources) }
