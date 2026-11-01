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
    implementation(libs.kotlin.reflect)
    implementation(libs.snakeyaml.engine)
    implementation(libs.json.schema.validator)
    testImplementation(libs.bundles.kotest)
    testImplementation(testFixtures(project(":packages:kotlin:ttr-parser")))
}
tasks.test {
    useJUnitPlatform()
    inputs.dir("../../../tests/conformance/fixtures")
    inputs.file("../../grammar/tests/canonical-schema-vectors.json")
}
