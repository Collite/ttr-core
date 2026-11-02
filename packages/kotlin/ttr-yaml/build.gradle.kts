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
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":packages:kotlin:ttr-parser"))
    api(libs.kotlinx.ser.json)
    implementation(libs.kotlin.reflect)
    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)
    testImplementation(libs.bundles.kotest)
    testImplementation(testFixtures(project(":packages:kotlin:ttr-parser")))
    testImplementation(project(":packages:kotlin:ttr-semantics"))
    testImplementation(testFixtures(project(":packages:kotlin:ttr-semantics")))
}

// Explicit authoring command only. Neither test/check nor CI generates its expected twins.
tasks.register<JavaExec>("generateTwins") {
    group = "verification"
    description = "Generate canonical YAML twins for review; commit them before running conformance"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.tatrman.ttr.yaml.GenerateYamlTwins")
}
tasks.test {
    useJUnitPlatform()
    inputs.dir("../../../tests/conformance/fixtures")
    inputs.dir("../../../tests/conformance/yaml-cases")
    inputs.dir("../../../tests/conformance/yaml-migration")
    inputs.file("../../grammar/tests/canonical-schema-vectors.json")
}

application {
    mainClass.set("org.tatrman.ttr.yaml.YamlMigrationCli")
    applicationName = "ttr-yaml-migrate"
    applicationDefaultJvmArgs = listOf("-Dslf4j.internal.verbosity=ERROR")
}

// Explicit fixture authoring only; certification/CI reads committed expectations.
tasks.register<JavaExec>("generateMigrationExpectations") {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.tatrman.ttr.yaml.GenerateYamlMigrationExpectations")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-yaml", version.toString())
    pom {
        name.set("TTR Canonical YAML")
        description.set("Canonical YAML codec and migration bridge for the TTR modelling DSL")
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

// application installs a local launcher; Maven consumers get library artifacts only.
tasks.register("verifyLibraryPublication") {
    dependsOn("generatePomFileForMavenPublication")
    doLast {
        val publication = publishing.publications.getByName<MavenPublication>("maven")
        check(publication.artifacts.toList().all { it.extension == "jar" }) {
            "ttr-yaml must not publish application distributions"
        }
        check(publication.artifacts.map { it.classifier }.toSet() == setOf(null, "sources", "javadoc"))
        val pom =
            layout.buildDirectory
                .file("publications/maven/pom-default.xml")
                .get()
                .asFile
                .readText()
        check("ttr-metadata" !in pom && "slf4j-nop" !in pom)
        println("PASS: ttr-yaml library JAR/sources/javadoc publication and POM")
    }
}
