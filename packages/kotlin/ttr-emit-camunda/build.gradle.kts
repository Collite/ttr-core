// SPDX-License-Identifier: Apache-2.0
// AU exporter: plugin-owned TTR-M manifests; recipe renderers are added by target phase.
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
}

dependencies {
    implementation(project(":packages:kotlin:ttrp-emit-spi"))
    implementation(libs.kotlinx.ser.json)
    testImplementation(project(":packages:kotlin:ttrp-conform"))
    // Language parsers/interpreters only in tests; no target engine/SDK in the exporter artifact.
    testImplementation("org.camunda.feel:feel-engine:1.19.3")
    testImplementation("org.camunda.bpm.juel:camunda-juel:7.24.0")
    testImplementation("org.apache.groovy:groovy:5.0.3")
    testImplementation("org.camunda.spin:camunda-spin-dataformat-json-jackson:7.24.0")
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.json.schema.validator)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-emit-camunda", version.toString())
    pom {
        name.set("TTR-P Emit — camunda")
        description.set("Automation recipe exporter and executor manifests: camunda")
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
