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
    api(project(":packages:kotlin:ttr-profile"))
    // The generated header (GeneratedHeader/GeneratedInputs/Hashes) and, through it, the canonical TtrRenderer.
    api(project(":packages:kotlin:ttr-metadata"))
    api(project(":packages:kotlin:ttr-writer"))
    // MW-P5 S5.2 (⚑MW-P5-S52-1 (a) → ✅D-MW-81): the `turtle`/`rdfxml` front-ends parse with Jena RIOT. Only ttr-import
    // takes Jena; ttr-metadata's MD3 ban stands. RDF Protobuf is never read (as ttr-metadata's tests); Thrift stays,
    // since RIOT's initialisation loads it.
    implementation(libs.jena.arq) {
        exclude(group = "com.google.protobuf")
    }
    testImplementation(libs.bundles.kotest)
    // MW-P5 S5.3: the XMI round trip compares the parser's AST dump and the semantics dump (the P2 oracle).
    testImplementation(testFixtures(project(":packages:kotlin:ttr-parser")))
    testImplementation(testFixtures(project(":packages:kotlin:ttr-semantics")))
}

// MW-P5 S5.1 (contracts §3): the profiles ttr-core ships live in the repository's top-level `profiles/` and are bundled
// as `/profiles/<name>/<major>.yaml`, so `--profile om-tables/1` resolves without a file.
val shippedProfiles = rootProject.layout.projectDirectory.dir("profiles")
tasks.processResources {
    from(shippedProfiles) { into("profiles") }
}

tasks.test {
    useJUnitPlatform()
    inputs.dir(shippedProfiles)
    inputs.file("../../grammar/schemas/profile.schema.json")
    // The reader fixtures the specs read in place (tests/conformance/readers), so an edited fixture reruns them.
    inputs.dir("../../../tests/conformance/readers")
    // R396-B: the optional third-party proof stays outside source control; env changes rerun the test task.
    val publicCdm = providers.environmentVariable("TTR_PDM_PUBLIC_CDM")
    inputs.property("pdPublicCdm", publicCdm.orElse(""))
    publicCdm.orNull?.takeIf { it.isNotBlank() }?.let {
        inputs.file(it).optional().withPropertyName("pdPublicCdmFile")
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
        providers.gradleProperty("signingInMemoryKey").isPresent
    ) {
        signAllPublications()
    }
    coordinates("org.tatrman", "ttr-import", version.toString())
    pom {
        name.set("TTR Import")
        description.set(
            "The TTR reader harness: format front-ends, shipped profiles and generated .ttrm packages with sidecars",
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
