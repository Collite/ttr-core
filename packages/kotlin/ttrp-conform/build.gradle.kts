// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Arrow Java needs this open on JDK 17+ (Netty allocator reflects into java.nio).
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    // RJ-P0 spike specs (tagged `Spike`) drive a live Postgres and are excluded from the default
    // test run. Opt in with `-PincludeSpike=true` (see src/test/spike/README.md).
    if (!project.hasProperty("includeSpike")) {
        systemProperty("kotest.tags", "!Spike")
    }
}

dependencies {
    // ttrp-emit exposes ttrp-graph (api) → TtrpGraph + NormalizedGraphJson for the eval comparator.
    implementation(project(":packages:kotlin:ttrp-emit"))
    // PL-P5.S2 — the H-6 emit-determinism kit operates over the emit SPI (TtrEmitPlugin/EmitRequest/EmitResult).
    implementation(project(":packages:kotlin:ttrp-emit-spi"))
    implementation(libs.tomlj) // eval corpus (versioned fixture) loader
    implementation(libs.arrow.vector)
    runtimeOnly(libs.arrow.memory.netty)
    testImplementation(libs.bundles.kotest)
    // PL-P5.S2 — the determinism-kit proof runs the real bash plugin (must PASS); a timestamping fake (must FAIL).
    testImplementation(project(":packages:kotlin:ttr-emit-bash"))
    // PL-P5.S3 — the Kestra plugin is certified by the same kit (the Q-6 permanent guard: must PASS).
    testImplementation(project(":packages:kotlin:ttr-emit-kestra"))
    // PL-P5.S4 — the Airflow 3 plugin (native binding) is certified by the same kit (must PASS).
    testImplementation(project(":packages:kotlin:ttr-emit-airflow3"))
    // RJ-P0 divergence spike only (tag `Spike`, off by default): JDBC to the live ttrp-pg +
    // YAML corpus loader. Test scope — never leaks into the published conform artifact.
    testImplementation(libs.postgresql)
    testImplementation(libs.snakeyaml)
    // MD dot-path S5-B write round-trip (tag `Spike`, off by default): lower an assignment to a
    // StoreNode (ttrp-emit) + unparse to DML (ttr-translator) + MD fixtures, then execute on live PG.
    testImplementation(project(":packages:kotlin:ttr-translator"))
    testImplementation(testFixtures(project(":packages:kotlin:ttr-translator")))
    testImplementation(testFixtures(project(":packages:kotlin:ttr-semantics")))
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
                name.set("TTR-P Conformance")
                description.set("Q9 conformance harness (S3) for TTR-P")
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
