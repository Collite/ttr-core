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

// Keep the two distributions completely separate: neither engine may borrow the other's classes.
val embeddedEngines =
    listOf("camunda" to "7.24.0", "cibseven" to "2.2.0").associate { (engine, version) ->
        engine to
            configurations.create("${engine}EmbeddedEngine") {
                isCanBeConsumed = false
                isCanBeResolved = true
                dependencies.add(project.dependencies.create("org.$engine.bpm:$engine-engine:$version"))
                dependencies.add(project.dependencies.create("org.$engine.bpm:$engine-engine-plugin-connect:$version"))
                dependencies.add(project.dependencies.create("org.$engine.bpm:$engine-engine-plugin-spin:$version"))
                dependencies.add(
                    project.dependencies.create("org.$engine.connect:$engine-connect-http-client:$version"),
                )
                dependencies.add(
                    project.dependencies.create("org.$engine.spin:$engine-spin-dataformat-json-jackson:$version"),
                )
                dependencies.add(project.dependencies.create("org.apache.groovy:groovy-jsr223:5.0.3"))
                dependencies.add(project.dependencies.create("org.apache.groovy:groovy-json:5.0.3"))
                dependencies.add(project.dependencies.create("com.h2database:h2:2.3.232"))
                dependencies.add(project.dependencies.create("org.slf4j:slf4j-simple:2.0.17"))
            }
    }

tasks.test {
    embeddedEngines.forEach { (engine, classpath) ->
        inputs.files(classpath)
        doFirst { systemProperty("$engine.embedded.classpath", classpath.asPath) }
    }
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
