plugins { application }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven {
        url = uri(providers.gradleProperty("artifactRepo").orElse("https://maven.pkg.github.com/Collite/ttr-core").get())
        if (!providers.gradleProperty("artifactRepo").isPresent) {
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
        content { includeGroup("org.tatrman") }
    }
    mavenCentral { content { excludeGroup("org.tatrman") } }
}
dependencies { implementation("org.tatrman:ttr-yaml:${providers.gradleProperty("codecVersion").get()}") }
application { mainClass.set("CanonicalYamlConsumer") }

// Assert resolved artifacts, rather than merely inspecting declared dependencies.
tasks.register("verifyClosure") {
    doLast {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        val names = artifacts.map { it.moduleVersion.id.name }
        check("ttr-parser" in names && "ttr-yaml" in names)
        check(names.none { it.startsWith("ttr-metadata") || it.startsWith("slf4j-") && it != "slf4j-api" })
        check(artifacts.all { it.extension == "jar" && it.classifier == null })
        for (artifact in artifacts.filter { it.moduleVersion.id.group == "org.tatrman" }) {
            check(artifact.moduleVersion.id.version == providers.gradleProperty("codecVersion").get())
        }
        println("PASS: published JAR runtime closure; no metadata, distributions or SLF4J provider")
    }
}
tasks.named("run") { dependsOn("verifyClosure") }
