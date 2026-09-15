// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    application
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Arrow Java (via ttrp-conform's ArrowIo, used by HeroConformLiveTest) needs nio access.
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}

// The thin `ttrp check` front-half dispatch (S2 — the full build/run/explain/conform
// CLI, and its framework choice, land in P3). Hand-rolled arg dispatch, no framework.
application {
    mainClass = "org.tatrman.ttrp.cli.MainKt"
    // Same nio-access requirement as the test task above (Arrow Java, via ttrp-conform's
    // ArrowIo) — without it the installed/distributed `ttrp-cli` binary crashes on `conform`.
    applicationDefaultJvmArgs = listOf("--add-opens=java.base/java.nio=ALL-UNNAMED")
}

dependencies {
    implementation(project(":packages:kotlin:ttrp-frontend"))
    implementation(project(":packages:kotlin:ttrp-graph"))
    implementation(project(":packages:kotlin:ttrp-emit"))
    // PL-P5.S1 — the emit SPI + the built-in bash plugin (the launcher is emitted through the SPI, not inline).
    implementation(project(":packages:kotlin:ttrp-emit-spi"))
    runtimeOnly(project(":packages:kotlin:ttr-emit-bash"))
    // PL-P5.S3 — the Kestra emit plugin, in-tree (a built-in target: `emit-determinism --plugin org.tatrman:ttr-emit-kestra`).
    runtimeOnly(project(":packages:kotlin:ttr-emit-kestra"))
    // PL-P5.S4 — the Airflow 3 emit plugin, in-tree (built-in target org.tatrman:ttr-emit-airflow3).
    runtimeOnly(project(":packages:kotlin:ttr-emit-airflow3"))
    implementation(project(":packages:kotlin:ttrp-conform"))
    implementation(project(":packages:kotlin:ttr-metadata"))
    // PL-P1.S2: `ttr fetch` writes archives into the snapshot cache.
    implementation(project(":packages:kotlin:ttr-snapshot"))
    // PL-P2.S7: `ttr deploy` packs the built bundle into its verbatim F-lite tar (§6). Pinned
    // commons-compress (same as the snapshot writer) keeps the packed tar — and thus its bundleHash —
    // byte-deterministic across builds of the same bundle.
    implementation(libs.apache.commons.compress.snapshot)
    implementation(libs.kotlinx.ser.json)
    implementation(libs.clikt)
    // S6-B: the connected-mode member catalog (`--connected`) talks to ttr-designer-server's ttrm/*
    // over WS. The Ktor client lives here (NOT ttr-metadata — its runtime classpath bans io.ktor).
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.coroutines.core)
    // PL-P5.S2 — verify a plugin jar's detached OpenPGP signature (`<jar>.asc`) before isolated load (H-6).
    implementation(libs.bundles.bouncycastle)
    testImplementation(libs.bundles.kotest)
    // review-071 T-P1: MdConformLiveTest self-seeds md_seed.sql via JDBC so the live read conform is
    // independent of any prior write suite's mutations (the same driver ttrp-conform uses).
    testImplementation(libs.postgresql)
    // PL-P1.S3: validate the GENERATED v2 manifest against the PL-P0 JSON Schema.
    testImplementation(libs.json.schema.validator)
    // Shared world/model fixture project (contracts §8) for the CLI component test.
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
    // S6-B connected-compile E2E: boot a real ttr-designer-server on a port + an in-memory H2 member DB.
    testImplementation(project(":packages:kotlin:ttr-designer-server"))
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.h2)
}

ktlint {
    filter {
        exclude("**/generated/**")
        exclude { it.file.path.contains("/generated-src/antlr/") }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("TTR-P CLI")
                description.set("the ttrp binary (S2): build/run/explain/conform")
                url.set("https://github.com/Collite/ttr-core")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Bora Perusic")
                        email.set("boraperusic@gmail.com")
                        organization.set("Collite")
                        organizationUrl.set("https://github.com/Collite")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/Collite/ttr-core.git")
                    developerConnection.set("scm:git:git@github.com:Collite/ttr-core.git")
                    url.set("https://github.com/Collite/ttr-core")
                }
            }
        }
    }
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
