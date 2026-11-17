// SPDX-License-Identifier: Apache-2.0
// AU-export S1.5.9 (review-340): passes a module's published `runtimeClasspath` to its `test` task as the system
// property `ttr.runtimeClasspath`: the components' ids (`group:name:version`, `project :path`), comma-separated,
// sorted. A spec reads what the module ships with, not the test classpath (kotest brings its own mordant). Resolved
// when the test runs; the classpath is a declared input, so a dependency change re-runs the spec.
// Apply with `apply(from = ...)` after the plugins block.
val runtime = configurations.named("runtimeClasspath")
val components =
    runtime.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
        artifacts.map { it.id.componentIdentifier.displayName }.distinct().sorted()
    }
tasks.named<Test>("test") {
    inputs.files(runtime).withPropertyName("publishedRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-Dttr.runtimeClasspath=${components.get().joinToString(",")}") },
    )
}
