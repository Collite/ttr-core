// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `java-test-fixtures`
    application
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
}

application {
    // Stdio LSP entry point (VS Code / IntelliJ hosts). `installDist` produces
    // build/install/ttrp-lsp/bin/ttrp-lsp — the launcher the Stage-4.3 extension spawns.
    mainClass.set("org.tatrman.ttrp.lsp.MainKt")
    applicationName = "ttrp-lsp"
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    // LSP4J is `api`: it appears in the LanguageServer/LanguageClient surface the
    // test fixtures + Stage 5.1 WS transport consume.
    api(libs.lsp4j)
    implementation(project(":packages:kotlin:ttrp-frontend"))
    // Stage 4.2 custom methods delegate to the Phase-2/3 libraries (S4: the LSP
    // serializes their output, it never recomputes its own semantics).
    implementation(project(":packages:kotlin:ttrp-graph"))
    implementation(project(":packages:kotlin:ttrp-cli"))
    // ResolvedWorld etc. — the authoring-context bundle serializes the resolved world (contracts §7).
    implementation(project(":packages:kotlin:ttr-metadata"))
    // `.ttrl` view-state sidecar (Stage 5.2): parse via ttr-parser's TtrlLoader, write
    // wholesale via ttr-writer's TtrlWriter (the family-wide, Kotlin-side-once asset, C1-f).
    implementation(project(":packages:kotlin:ttr-parser"))
    implementation(project(":packages:kotlin:ttr-writer"))

    // The in-memory paired-stream harness (the Kotlin twin of the TS PassThrough
    // harness) is a test fixture so Stage 4.2 specs reuse it (java-test-fixtures).
    // It binds the shared erp-project world, so it needs the front-half manifest types
    // and the metadata fixtures on the testFixtures classpath (ttrp-frontend is a main
    // `implementation`, not exposed to testFixtures).
    testFixturesApi(libs.lsp4j)
    testFixturesImplementation(project(":packages:kotlin:ttrp-frontend"))
    testFixturesImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))

    testImplementation(libs.bundles.kotest)
    // The shared erp-project world/model fixture (contracts §8) — the LSP resolves
    // the hero against it exactly as the CLI resolves a real project.
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
    // JSON-schema validation of the authoring-context bundle against the committed schema (T4.2.6).
    testImplementation("com.networknt:json-schema-validator:1.5.4")
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
                name.set("TTR-P LSP")
                description.set("one TTR-P LSP; stdio + WS transports")
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
