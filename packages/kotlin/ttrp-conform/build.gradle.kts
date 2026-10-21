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
    // AU-core-P2 · S2.4 — the recipe statements' second engine, embedded (RecipeDuckDbSpec; RecipeConform is JDBC-only).
    testRuntimeOnly(libs.duckdb.jdbc)
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

// AG-14 (2026-10-02): published in the `grammar` bundle (one api closure with ttr-metadata — PUBLISHING.md),
// to GitHub Packages on every grammar tag and to Maven Central on `-RELEASE` ones.
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttrp-conform", version.toString())
    pom {
        name.set("TTR-P Conformance")
        description.set("Q9 conformance harness (S3) for TTR-P")
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
