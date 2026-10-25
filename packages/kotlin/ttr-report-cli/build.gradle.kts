// SPDX-License-Identifier: Apache-2.0
import org.gradle.process.CommandLineArgumentProvider

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

application {
    mainClass = "org.tatrman.report.cli.MainKt"
    applicationName = "ttr-report"
}

tasks.jar { archiveBaseName.set("ttr-report-cli") }

tasks.test {
    useJUnitPlatform()
}

val distTestSourceSet =
    sourceSets.create("distTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
        resources.srcDir("src/test/resources")
    }
configurations["distTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["distTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

abstract class ReportDistributionArguments : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distribution: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-Dttr.report.dist=${distribution.get().asFile.absolutePath}")
}

val installedDistribution = tasks.named<Sync>("installDist")
val distTest by tasks.registering(Test::class) {
    description = "Checks the shipped report CLI jars in fresh JVMs."
    group = "verification"
    testClassesDirs = distTestSourceSet.output.classesDirs
    classpath = distTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    dependsOn(installedDistribution)
    shouldRunAfter(tasks.test)
    jvmArgumentProviders.add(
        objects.newInstance<ReportDistributionArguments>().apply {
            distribution.set(layout.dir(installedDistribution.map { it.destinationDir }))
        },
    )
}
tasks.check { dependsOn(distTest) }

dependencies {
    implementation(project(":packages:kotlin:ttr-metadata"))
    implementation(libs.clikt)
    implementation(libs.tomlj)
    implementation(project(":packages:kotlin:ttr-report"))
    implementation(project(":packages:kotlin:ttr-translator"))
    implementation(libs.slf4j.nop)
    // POI logs through Log4j; keep the executable's existing SLF4J/NOP policy out of the library.
    runtimeOnly(libs.log4j.to.slf4j)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.apache.poi.ooxml)
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
        description.set("Creates report templates from saved queries and renders offline CSV rows")
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
    artifact(tasks.named("distTar")) { classifier = "dist" }
}
