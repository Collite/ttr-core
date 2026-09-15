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
    // T7 — the component test runs the real command over the P1.2 fixture estate rather than a
    // copy of it, so the producer→consumer loop is closed against the fixture the compiler's own
    // suite uses. Passed as a property instead of a relative path so the test does not depend on
    // the working directory Gradle happens to pick.
    systemProperty(
        "fixtureEstate",
        rootProject.file("packages/kotlin/ttr-lexicon-compile/src/test/resources/estate").absolutePath,
    )
}

// RV-P3.1 T3 — `ttr-lexicon build <repoRoot> --out <path>`. clikt + the `application` plugin,
// following `ttrp-cli`'s module shape; `installDist` is what an estate's justfile invokes (T5).
application {
    mainClass = "org.tatrman.ttr.lexicon.cli.MainKt"
    // The command is `ttr-lexicon`, not `ttr-lexicon-cli` — the module is named for the artifact,
    // the binary for what an estate types. `installDist` therefore lays down
    // build/install/ttr-lexicon/bin/ttr-lexicon, which is the path recorded in T5 for p3-2.
    applicationName = "ttr-lexicon"
}

tasks.named<org.gradle.jvm.tasks.Jar>("jar") {
    // Two publishable jars in one lane must not collide in a consumer's cache. The distribution
    // (installDist) keeps the plain `ttr-lexicon` name; only the Maven artifact is qualified.
    archiveBaseName.set("ttr-lexicon-cli")
}

dependencies {
    // The build entry point (LexiconBuild.run) and the packer. `api`: the CLI's own test surface
    // hands back a CompileResult, and a caller embedding the command sees both.
    api(project(":packages:kotlin:ttr-lexicon-compile"))
    // T1 ruling — the model is loaded through the composition that already exists
    // (MetadataLoader over FileBasedSource/LocalFsStorage). No third model loader.
    implementation(project(":packages:kotlin:ttr-metadata"))
    // The archive id is `SnapshotId.of(bytes)` — never a second hash of the same bytes.
    implementation(project(":packages:kotlin:ttr-snapshot"))
    implementation(libs.clikt)

    testImplementation(libs.bundles.kotest)
}

// RV-P3.1 T6 — rides the `bundle grammar` lockstep lane with the modules it calls. The lane
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
    coordinates("org.tatrman", "ttr-lexicon-cli", version.toString())
    pom {
        name.set("TTR Lexicon CLI")
        description.set("Builds an estate's deterministic `kind: \"lexicon\"` archive from a repo root")
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
