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
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("updateGolden", System.getProperty("updateGolden") ?: "false")
}
dependencies {
    api(project(":packages:kotlin:ttrp-emit"))
    api(libs.kotlinx.ser.json)
    implementation(libs.commonmark)
    api(project(":packages:kotlin:ttr-metadata"))
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.json.schema.validator)
}

sourceSets.main { resources.srcDir(rootProject.file("docs/features/ttr-p/architecture/schemas")) }

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttrp-doc", version.toString())
    pom {
        name.set("TTR-P Documents")
        description.set("Literate recipe extraction, provenance, explanation rendering and AU annotation types")
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
