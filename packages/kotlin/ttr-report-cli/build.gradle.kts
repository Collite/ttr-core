// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
    application
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "org.tatrman.report.cli.MainKt"
    applicationName = "ttr-report"
}

tasks.jar { archiveBaseName.set("ttr-report-cli") }

dependencies {
    implementation(project(":packages:kotlin:ttr-metadata"))
    implementation(libs.clikt)
    implementation(libs.tomlj)
    implementation(project(":packages:kotlin:ttr-report"))
    implementation(project(":packages:kotlin:ttr-translator"))
    implementation(libs.slf4j.nop)
    testImplementation(libs.bundles.kotest)
}

// SO P2 — rides the `bundle grammar` lockstep lane with the modules it calls. The lane
// publishes to BOTH the GH Packages staging repo and Maven Central in one Gradle invocation, so
// this module needs both publication paths configured or the whole set fails (publish.yml turns
// `:publish` into `:publishAllPublicationsToGitHubPackagesRepository` AND
// `:publishAndReleaseToMavenCentral` — a module with only one of them breaks the other lane).
mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-report-cli", version.toString())
    pom {
        name.set("TTR Report CLI")
        description.set("Renders open report templates from offline CSV rows")
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

// The distribution is consumable without rebuilding the repository.
publishing.publications.withType<MavenPublication>().configureEach {
    artifact(tasks.named("distZip")) { classifier = "dist" }
}
