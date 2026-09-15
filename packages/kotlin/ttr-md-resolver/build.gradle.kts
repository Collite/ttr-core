// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // §3 DTOs are @Serializable (public contract, MDS6)
    alias(libs.plugins.ktlint)
    `java-library`
    `java-test-fixtures` // InMemoryMemberSnapshot + golden helpers reused by S3/S6/S7
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// MD dot-path S2 — the resolver core (MDS1). Standalone: it consumes ttr-semantics' MdModel and
// owns its own PathComponent + path-text parser, so it never depends on ttrp-frontend — S3 wires
// the dependency the OTHER way (ttrp-frontend → ttr-md-resolver). FORBIDDEN deps (MDS1, enforced
// by review + the absence below): Ktor / Kotlin MCP SDK (that lands in S7's ttr-md-agent) and
// Calcite (lowering is S4's ttr-translator). This module is pure resolution logic — no I/O, no
// transport, no plan emission.
dependencies {
    // api: CanonicalPath exposes AggKind (from ttr-semantics.md); ttr-parser rides along
    // transitively (ttr-semantics api-exports it) but is named explicitly per S2-A1.
    api(project(":packages:kotlin:ttr-semantics"))
    api(project(":packages:kotlin:ttr-parser"))
    implementation(libs.kotlinx.ser.json)

    // Test fixtures (InMemoryMemberSnapshot) may reference the main-scope catalog interfaces.
    testFixturesApi(project(":packages:kotlin:ttr-semantics"))

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.ser.json)
    // Shared `.ttrm` sales-model fixture (S1-A1) + this module's own fixtures (InMemoryMemberSnapshot).
    testImplementation(testFixtures(project(":packages:kotlin:ttr-semantics")))
    testImplementation(testFixtures(project(":packages:kotlin:ttr-md-resolver")))
}

ktlint {
    filter {
        exclude("**/generated/**")
        exclude { it.file.path.contains("/generated-src/antlr/") }
    }
}

// SV-P1 S4 — Maven Central (Central Portal) is the PUBLIC lane (RO-17). vanniktech
// owns the publication (adds the sources + javadoc jars Central requires) and the
// Central target; the GH Packages block below stays as the pre-release staging lane.
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-md-resolver", version.toString())
    pom {
        name.set("TTR MD Resolver")
        description.set("Dot-path constraint resolver (tokens → canonical MD path) for the TTR (Tatrman) DSL.")
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
