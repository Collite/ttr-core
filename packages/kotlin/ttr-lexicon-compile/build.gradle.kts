// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // The authored-side model + the compiled-artifact model/codec. `api`: a caller of
    // LexiconCompiler holds both a LexiconArea (in) and a CompiledLexicon (out).
    api(project(":packages:kotlin:ttr-lexicon"))
    // The TTR-M `def term … for: … forms: […]` sugar (grammar 4.4) — parsed, not re-parsed.
    implementation(project(":packages:kotlin:ttr-parser"))
    // The METADATA layer's source: displayLabel / labelPlural / aliases / valueLabels.
    implementation(project(":packages:kotlin:ttr-metadata"))
    // RV-P3.4 — md measures/dimensions/cubelets live in `MdModel`, not in `ttr-metadata`'s
    // `Model` (whose SchemaCode has no MD). The md half of the ref index reads them from here.
    implementation(project(":packages:kotlin:ttr-semantics"))
    // (a3) — the compiled lexicon is packed as its own `kind: "lexicon"` archive, by the
    // same deterministic writer the model snapshot uses.
    implementation(project(":packages:kotlin:ttr-snapshot"))

    testImplementation(libs.bundles.kotest)
}

// RV-P1.2 — the compiler half of the lexicon. Separate from `ttr-lexicon` because a SERVING
// consumer (lex-matcher, the resolver) reads the artifact and must not resolve ttr-parser,
// ttr-metadata and ttr-snapshot to do it. Published on the same `grammar` lockstep.
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-lexicon-compile", version.toString())
    pom {
        name.set("TTR Lexicon Compiler")
        description.set("Compiles the declared + metadata lexicon layers into the deterministic lexicon archive")
        inceptionYear.set("2026")
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
