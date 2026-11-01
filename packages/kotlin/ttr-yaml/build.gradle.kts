// SPDX-License-Identifier: Apache-2.0
plugins {
    base
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
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
    inputs.file("../../grammar/tests/canonical-schema-vectors.json")
}

application {
    mainClass.set("org.tatrman.ttr.yaml.YamlMigrationCli")
    applicationName = "ttr-yaml-migrate"
}
