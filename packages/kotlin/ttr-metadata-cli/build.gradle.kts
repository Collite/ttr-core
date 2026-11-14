// SPDX-License-Identifier: Apache-2.0
import java.security.MessageDigest
import java.util.zip.ZipFile

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
    inputs.dir("../ttr-metadata/src/test/resources/md-hop")
    inputs.dir("../ttr-metadata/src/testFixtures/resources/fixtures/xm-world")
    inputs.dir("../ttr-metadata/src/test/resources/xm-goldens")
    // MW S3.7 T2 (§26 (c)): the whole conformance tree (dialect, guard, the DONE 1 index, md-hop).
    inputs.dir("../../../tests/conformance")
    // MW S3.5: the code-catalogue drift scan reads these sources; the CLI specs read the dialect trees.
    inputs.dir("../ttr-yaml/src/main/kotlin")
    inputs.dir("../ttr-profile/src/main/kotlin")
    inputs.dir("../ttr-metadata/src/main/kotlin")
    systemProperty("metadata.expectedVersion", project.version.toString())
    // MW S3.5 T6: one spec drives the installed distribution.
    dependsOn(tasks.named("installDist"))
    // MW S3.7 T3: CliJarSpec runs the released jar.
    dependsOn("cliJarChecksum")
    systemProperty(
        "metadata.cliJar",
        layout.buildDirectory
            .file("cli-jar/ttr-metadata-cli-${project.version}.jar")
            .get()
            .asFile.path,
    )
    systemProperty(
        "metadata.installedBinary",
        layout.buildDirectory
            .file("install/ttr-metadata/bin/ttr-metadata")
            .get()
            .asFile.path,
    )
}

application {
    mainClass = "org.tatrman.ttr.metadata.cli.MainKt"
    applicationName = "ttr-metadata"
}

tasks.jar { archiveBaseName.set("ttr-metadata-cli") }

// MW S3.7 T3 (contracts §7) — the self-contained CLI jar the grammar release carries as `ttr-metadata-cli-<v>.jar`, so
// ai-models pins it like `model-validator.jar` (`java -jar`). Every runtime jar is unpacked into it; the dependencies'
// `META-INF/services` files are merged (mordant's terminal providers and jackson's codecs collide, and a first-wins copy
// would drop registrations); signatures and module descriptors are left out. publish.yml attaches it with its SHA-256.
val cliJarServices by tasks.registering {
    val runtime = configurations.runtimeClasspath
    val out = layout.buildDirectory.dir("generated/cli-jar-services")
    inputs.files(runtime)
    outputs.dir(out)
    doLast {
        val merged = sortedMapOf<String, LinkedHashSet<String>>()
        runtime.get().files.filter { it.isFile && it.name.endsWith(".jar") }.sorted().forEach { jar ->
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .forEach { entry ->
                        val lines =
                            zip
                                .getInputStream(entry)
                                .bufferedReader()
                                .readLines()
                                .map { it.substringBefore('#').trim() }
                                .filter { it.isNotEmpty() }
                        merged.getOrPut(entry.name.removePrefix("META-INF/services/")) { linkedSetOf() } += lines
                    }
            }
        }
        val services = out.get().asFile.resolve("META-INF/services")
        services.deleteRecursively()
        services.mkdirs()
        merged.forEach { (name, lines) -> services.resolve(name).writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}

val cliJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "The self-contained ttr-metadata-cli-<version>.jar (java -jar) the grammar release carries"
    archiveFileName.set("ttr-metadata-cli-${project.version}.jar")
    destinationDirectory.set(layout.buildDirectory.dir("cli-jar"))
    manifest {
        attributes("Main-Class" to application.mainClass.get(), "Implementation-Version" to project.version.toString())
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // The merged service files first: with EXCLUDE the first copy of a path wins.
    from(cliJarServices)
    from(sourceSets.main.map { it.output })
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.EC",
            "META-INF/INDEX.LIST",
            "module-info.class",
            "META-INF/versions/*/module-info.class",
        )
    }
}

/** `<jar>.sha256` beside it, `sha256sum` format (`<hex>  <file>`), for the release asset. */
val cliJarChecksum by tasks.registering {
    group = "distribution"
    val jar = cliJar.flatMap { it.archiveFile }
    val out = layout.buildDirectory.file("cli-jar/ttr-metadata-cli-${project.version}.jar.sha256")
    inputs.file(jar)
    outputs.file(out)
    doLast {
        val file = jar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        out.get().asFile.writeText("${digest.joinToString("") { "%02x".format(it) }}  ${file.name}\n")
    }
}
cliJar { finalizedBy(cliJarChecksum) }

val metadataVersion = project.version.toString()
val generateMetadataVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/metadata-version")
    inputs.property("version", metadataVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("org/tatrman/ttr/metadata/cli/version.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$metadataVersion\n")
    }
}
sourceSets.main { resources.srcDir(generateMetadataVersion) }

dependencies {
    implementation(project(":packages:kotlin:ttr-metadata"))
    implementation(libs.clikt)
    implementation(libs.tomlj)
    implementation(libs.apache.commons.compress)
    implementation(libs.kotlinx.ser.json)
    // MW S3.7 T3: a CLI binds SLF4J's no-op provider, so the released jar is silent on stderr (as ttr-report-cli).
    runtimeOnly(libs.slf4j.nop)
    testImplementation(libs.bundles.kotest)
}

// SO S0.3 — rides the `bundle grammar` lockstep lane with the modules it calls. The lane
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
    coordinates("org.tatrman", "ttr-metadata-cli", version.toString())
    pom {
        name.set("TTR Metadata CLI")
        description.set("Loads a model checkout and reports strict loader diagnostics")
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
