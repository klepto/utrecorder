import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import dev.klepto.utrecorder.build.lib
import dev.klepto.utrecorder.build.registerLayoutTasks
import dev.klepto.utrecorder.build.uscript.CompileUscriptTask
import dev.klepto.utrecorder.build.uscript.UCC_SERVICE
import dev.klepto.utrecorder.build.uscript.UccService
import dev.klepto.utrecorder.build.uscript.UnrealScriptExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
    `java-library`
    id("utrecorder.embedded-artifacts")
}

val embeddedArtifacts = extensions.getByType<EmbeddedArtifactsExtension>()
embeddedArtifacts.namespace.convention("unrealscript")

val unrealScript = extensions.create<UnrealScriptExtension>("unrealscript")
unrealScript.sourceDirectory.convention(layout.projectDirectory.dir("src/main/uscript"))
unrealScript.homeDirectory.convention(layout.settingsDirectory.dir(".utrecorder"))

val uccService =
    gradle.sharedServices.registerIfAbsent(UCC_SERVICE, UccService::class) {
        maxParallelUsages.set(1)
    }

val unrealScriptCompiler =
    configurations.dependencyScope("unrealScriptCompiler") {
        description = "The Unreal Tournament compiler the UnrealScript packages are built by."
    }

val unrealScriptCompilerClasspath =
    configurations.resolvable("unrealScriptCompilerClasspath") {
        description = "The runtime classpath of the Unreal Tournament compiler the UnrealScript packages are built by."
        extendsFrom(unrealScriptCompiler.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
        }
    }

dependencies {
    unrealScriptCompiler.name(project(":utrecorder-ut99"))
    unrealScriptCompiler.name(lib("logback.classic"))
}

val patchVersionOverride =
    providers
        .systemProperty("utrecorder.patch_version")
        .orElse(providers.environmentVariable("UTRECORDER_PATCH_VERSION"))

registerLayoutTasks(
    elements = unrealScript.packages,
    sourceDirectory = unrealScript.sourceDirectory.get(),
    taskName = "compileUscript",
    description = "Compiles every UnrealScript package of this module.",
    taskType = CompileUscriptTask::class,
    outputDirectory = { it.outputDirectory },
) { uscriptPackage ->
    description = "Compiles the ${uscriptPackage.name} UnrealScript package into ${uscriptPackage.name}.u."

    sources = uscriptPackage.sources.convention(unrealScript.sourceDirectory.dir(uscriptPackage.name))
    packageName = uscriptPackage.name
    editPackages = uscriptPackage.editPackages
    patchVersion = patchVersionOverride

    usesService(uccService)
    compilerClasspath.from(unrealScriptCompilerClasspath)
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    homeDirectory = unrealScript.homeDirectory

    outputDirectory = layout.buildDirectory.dir("uscript/${uscriptPackage.name}")
    logDirectory = layout.buildDirectory.dir("uscript-logs/${uscriptPackage.name}")
}
