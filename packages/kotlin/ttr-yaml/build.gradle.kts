// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":packages:kotlin:ttr-parser"))
    api(libs.kotlinx.ser.json)
    implementation(project(":packages:kotlin:ttr-writer"))
    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)
    testImplementation(libs.bundles.kotest)
}
tasks.test {
    useJUnitPlatform()
    inputs.dir("../../../tests/conformance/fixtures")
}
