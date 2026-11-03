// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    `maven-publish`
    alias(libs.plugins.maven.publish.vanniktech)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":packages:kotlin:ttr-yaml"))
    testImplementation(libs.bundles.kotest)
}
tasks.test {
    useJUnitPlatform()
    inputs.dir("../../../tests/conformance/fixtures")
    inputs.file("../../grammar/schemas/profile.schema.json")
    inputs.file("build.gradle.kts")
    inputs.file(rootProject.file("settings.gradle.kts"))
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-profile", version.toString())
    pom {
        name.set("TTR Profiles")
        description.set("Located profile loading and canonical identity for the TTR modelling DSL")
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

// Inspect the resolved production graph, so a transitive metadata edge also fails the direction check.
val runtimeProjects =
    configurations.runtimeClasspath.map { configuration ->
        configuration.incoming.resolutionResult.allComponents
            .mapNotNull {
                (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
            }.sorted()
            .joinToString(",")
    }
tasks.test {
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.property("runtimeProjects", runtimeProjects)
    systemProperty(
        "profile.jar",
        tasks.jar
            .flatMap { it.archiveFile }
            .get()
            .asFile.absolutePath,
    )
    doFirst { systemProperty("profile.runtimeProjects", runtimeProjects.get()) }
}
