import com.github.gradle.node.npm.task.NpmTask
import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import dev.klepto.utrecorder.build.web.GitCommitSource
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    `java-library`
    id("utrecorder.embedded-artifacts")
    id("com.github.node-gradle.node")
}

val embeddedArtifacts = extensions.getByType<EmbeddedArtifactsExtension>()
embeddedArtifacts.namespace.convention("web")

node {
    download = true
    distBaseUrl = null
    version = providers.fileContents(layout.projectDirectory.file(".nvmrc")).asText.map(String::trim)
    workDir = rootProject.layout.projectDirectory.dir(".gradle/nodejs")
    npmWorkDir = rootProject.layout.projectDirectory.dir(".gradle/npm")
    npmInstallCommand = "ci"
}

val gitCommit =
    providers
        .of(GitCommitSource::class.java) {
            parameters.repository = rootProject.layout.projectDirectory
        }.orElse("unknown")

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
        environment.put("UTRECORDER_COMMIT", gitCommit)

        inputs.files(webSources).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("version", project.version.toString())
        inputs.property("commit", gitCommit)
        outputs.dir(layout.buildDirectory.dir("web"))
    }

val webCheck =
    tasks.register<NpmTask>("webCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Type-checks, lints and tests the web site."

        dependsOn(tasks.npmInstall)
        npmCommand = listOf("run", "check")

        val marker = layout.buildDirectory.file("web-check/passed")
        inputs.files(webSources, "biome.json").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(marker)
        doLast { marker.get().asFile.writeText("") }
    }

embeddedArtifacts.artifacts.from(webBuild)

tasks.check {
    dependsOn(webCheck)
}
