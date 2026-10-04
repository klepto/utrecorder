import com.github.gradle.node.npm.task.NpmTask
import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    `java-library`
    id("utrecorder.embedded-artifacts")
    id("com.github.node-gradle.node")
}

val embeddedArtifacts = extensions.getByType<EmbeddedArtifactsExtension>()
embeddedArtifacts.namespace.convention("web")

val nodeVersionFile = layout.projectDirectory.file(".nvmrc")

node {
    download = true
    distBaseUrl = null
    version = providers.fileContents(nodeVersionFile).asText.map(String::trim)
    workDir = rootProject.layout.projectDirectory.dir(".gradle/nodejs")
    npmWorkDir = rootProject.layout.projectDirectory.dir(".gradle/npm")
    npmInstallCommand = "ci"
    fastNpmInstall = true
}

tasks.npmInstall {
    inputs.file(nodeVersionFile).withPathSensitivity(PathSensitivity.NONE)
}

val webSources =
    files(
        "src",
        "public",
        "index.html",
        "package.json",
        "package-lock.json",
        "tsconfig.json",
        "vite.config.ts",
    )

val webBuild =
    tasks.register<NpmTask>("webBuild") {
        group = LifecycleBasePlugin.BUILD_GROUP
        description = "Builds the web site with Vite."

        dependsOn(tasks.npmInstall)
        npmCommand = listOf("run", "build")
        environment.put("UTRECORDER_VERSION", project.version.toString())

        inputs.files(webSources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(nodeVersionFile).withPathSensitivity(PathSensitivity.NONE)
        inputs.property("version", project.version.toString())
        outputs.dir(layout.buildDirectory.dir("web"))
        outputs.cacheIf { true }
    }

val webCheck =
    tasks.register<NpmTask>("webCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Type-checks, lints and tests the web site."

        dependsOn(tasks.npmInstall)
        npmCommand = listOf("run", "check")

        val marker = layout.buildDirectory.file("web-check/passed")
        inputs.files(webSources, "biome.json").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(nodeVersionFile).withPathSensitivity(PathSensitivity.NONE)
        outputs.file(marker)
        outputs.cacheIf { true }
        doLast { marker.get().asFile.writeText("") }
    }

embeddedArtifacts.artifacts.from(webBuild)

tasks.check {
    dependsOn(webCheck)
}
