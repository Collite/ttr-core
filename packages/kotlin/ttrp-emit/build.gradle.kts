// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Golden-update workflow (GoldenSupport): `-DupdateGolden=true` rewrites goldens, then the
    // test fails asking for a clean re-run so the diff is reviewed. Propagate the flag into the
    // forked test JVM.
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}

dependencies {
    api(project(":packages:kotlin:ttrp-graph"))
    // The translation core (island → RelNode → SQL / plan.v1). Brings ttr-plan-proto
    // (plan.v1 wire types) transitively via its `api` dep. Calcite arrives transitively
    // too but is NEVER imported here — all Calcite engagement lives behind the published
    // `Translator.unparseFromRelNode` boundary (NoCalciteOutsideFacadeTest guards this).
    api(project(":packages:kotlin:ttr-translator"))
    // ResolvedEngine/ResolvedWorld etc. — ttrp-graph keeps ttr-metadata `implementation`,
    // so the resolved-world types aren't transitive; emit reads engine type/version off them.
    implementation(project(":packages:kotlin:ttr-metadata"))
    // MD dot-path S4-A read lowering (MdPathLowering): the resolved CanonicalPath (ttr-md-resolver)
    // + its md2db physical bindings (ttr-semantics.md). Both ride in transitively via the
    // ttrp-frontend→ttr-md-resolver→ttr-semantics `api` chain, but declared directly since emit
    // imports them by name (§8 lowering is owned here, not in ttr-translator — MDS1/MDS5).
    implementation(project(":packages:kotlin:ttr-md-resolver"))
    implementation(project(":packages:kotlin:ttr-semantics"))
    // RunManifest (contracts §5) — the shared bundle wire contract, consumed by both ttrp-cli
    // (assembler) and ttrp-conform (strict reader); lives here to break the cli↔conform cycle.
    implementation(libs.kotlinx.ser.json)

    testImplementation(libs.bundles.kotest)
    // PL-P0.S1 — validate the E-5 bundle-manifest v2 fixture against its JSON Schema (draft 2020-12).
    testImplementation(libs.json.schema.validator)
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
    // Shared MD arc fixtures (MdFixtures.salesBindings() — the md2db binding fixture, S4-A1).
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
                name.set("TTR-P Emit")
                description.set("island codegen, movement synthesis, bundle assembly for TTR-P")
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
