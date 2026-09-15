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
    // `ttrp explain` goldens: run with `-DupdateGolden=true` to (re)write, then review the diff.
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}

dependencies {
    api(project(":packages:kotlin:ttrp-frontend"))
    // The graph consumes resolved-world + model types (ResolvedWorld, Relation, QualifiedName)
    // directly, so ttr-metadata must be on the compile classpath (ttrp-frontend exposes it only
    // transitively at runtime).
    implementation(project(":packages:kotlin:ttr-metadata"))
    // Stage 2.2: engine-type capability manifests are shipped JSON (kotlinx-serialization).
    // Reviewable choice (contracts leaves the type-manifest format open, T6-c) — kept behind
    // the ManifestSource interface so only the loader changes if overturned to TTR-ish text.
    implementation(libs.kotlinx.ser.json)
    testImplementation(libs.bundles.kotest)
    // Shared world/model fixture project (contracts §8): consume, never duplicate.
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
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
                name.set("TTR-P Graph + Normalizer")
                description.set("graph construction + normalizer (T8 rewrites) for TTR-P")
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
