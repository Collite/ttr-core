// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    // `ttrp explain` goldens: run with `-DupdateGolden=true` to (re)write, then review the diff.
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
    // DisplayLeavesSpec reads the shared action-display fixture project in ttrp-frontend's test resources (found by
    // walking up the tree): declare it, so editing a fixture re-runs this task instead of leaving it UP-TO-DATE.
    inputs
        .dir(rootProject.file("packages/kotlin/ttrp-frontend/src/test/resources/display"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("displayFixture")
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

// AG-14 (2026-10-02): published in the `grammar` bundle (one api closure with ttr-metadata — PUBLISHING.md),
// to GitHub Packages on every grammar tag and to Maven Central on `-RELEASE` ones.
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttrp-graph", version.toString())
    pom {
        name.set("TTR-P Graph + Normalizer")
        description.set("graph construction + normalizer (T8 rewrites) for TTR-P")
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
