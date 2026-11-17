// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    application
}

// AU-export S1.5.9 review-340: `ttr.runtimeClasspath` for Slf4jProviderSpec.
apply(from = rootProject.file("gradle/runtime-classpath-components.gradle.kts"))

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

application {
    mainClass = "org.tatrman.ttr.designer.server.ApplicationKt"
}

dependencies {
    implementation(project(":packages:kotlin:ttr-metadata")) // NO ttr-metadata-git (MD3)
    // The TTR-P LSP mounted at /lsp (MD8): the host serves the SAME Phase-4 server object,
    // it never forks LSP logic. lsp4j rides in transitively (ttrp-lsp exposes it `api`).
    implementation(project(":packages:kotlin:ttrp-lsp"))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.ser.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":packages:kotlin:ttr-metadata")))
    testImplementation(libs.h2) // S6-B — in-memory member DataSource for the getMembers WS spec
}
