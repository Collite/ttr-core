// SPDX-License-Identifier: Apache-2.0
// The bash F-lite emit plugin (contracts §5/§8, PL-P5.S1) — the PROVING plugin: today's `run.sh` renderer
// extracted verbatim behind the TtrEmitPlugin SPI. `targetId = "bash"`. Ships the bash executor-type manifest
// (§7 subset) and registers via META-INF/services. The SPI is proven by extraction, not invented.
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
    testImplementation(libs.bundles.kotest)
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-emit-bash", version.toString())
    pom {
        name.set("TTR-P Emit — bash")
        description.set(
            "The bash F-lite emit plugin (org.tatrman:ttr-emit-bash) — the wave-parallel `run.sh` executor " +
                "(contracts §5) behind the E-1/EQ-1 emit SPI. The proving plugin: extracted, byte-identical.",
        )
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
