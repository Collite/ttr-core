// SPDX-License-Identifier: Apache-2.0
import com.google.protobuf.gradle.id
import java.util.zip.ZipFile

// ttr-plan-proto — canonical wire formats for the TTR-P plan pipeline
// (plan.v1 / transdsl.v1 / dfdsl.v1). Extracted from kantheon shared/proto at
// f2e2efb (2026-07-06); tatrman is now the canonical owner (TR-3, contracts §2).
// Message-only protos (no `service` blocks) → java + kotlin builtins, no grpc.

plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

val protobufVersion =
    libs.versions.protobuf
        .asProvider()
        .get()

dependencies {
    // protobuf-kotlin brings protobuf-java transitively; consumers get both the
    // Java message classes and the Kotlin DSL builders on the api classpath.
    api(libs.protobuf.kotlin)

    testImplementation(libs.bundles.kotest)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // `java` builtin is auto-enabled by the java plugin; add kotlin DSL.
                id("kotlin") { }
            }
        }
    }
}

ktlint {
    // Generated proto Kotlin sources are not human-authored — exclude.
    filter {
        exclude { it.file.path.contains("/build/generated/") }
    }
}

// Guard the import-path contract (§4.1): the published jar MUST bundle the 6
// .proto files so kantheon's protoc include path resolves them. A protobuf
// plugin upgrade could silently drop them — fail `check` if the count drifts.
// (6 = the 5 plan/transdsl/dfdsl protos + translate/v1/translator.proto, the
// Language/SqlDialect enum stub the translator compiles against — blocker A2-1.)
val verifyProtosInJar =
    tasks.register("verifyProtosInJar") {
        dependsOn(tasks.jar)
        val jarProvider = tasks.jar.flatMap { it.archiveFile }
        doLast {
            val count =
                ZipFile(jarProvider.get().asFile).use { zip ->
                    zip.entries().asSequence().count { it.name.endsWith(".proto") }
                }
            check(count == 6) {
                "Expected 6 .proto files bundled in the jar (import-path contract §4.1), found $count."
            }
        }
    }
tasks.named("check") { dependsOn(verifyProtosInJar) }

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
    coordinates("org.tatrman", "ttr-plan-proto", version.toString())
    pom {
        name.set("TTR Plan Proto")
        description.set(
            "Canonical wire formats for the TTR-P plan pipeline (plan.v1 / transdsl.v1 / " +
                "dfdsl.v1); generated classes plus the .proto files as jar resources.",
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
