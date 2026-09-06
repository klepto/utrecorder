import dev.klepto.utrecorder.build.createFromLayout
import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import dev.klepto.utrecorder.build.uscript.CompileUscriptTask
import dev.klepto.utrecorder.build.uscript.UCC_SERVICE
import dev.klepto.utrecorder.build.uscript.UccService
import dev.klepto.utrecorder.build.uscript.UnrealScriptExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    `java-library`
    id("utrecorder.embedded-artifacts")
}

/**
 * The project holding the compiler this plugin forks. Named by path rather than taken as a dependency, because the
 * build that compiles this plugin cannot see the build that holds the project.
 */
val compilerProject = ":utrecorder-ut99"

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
    unrealScriptCompiler.name(project(compilerProject))
}

// The version the compiler defaults to is compiled into the jar, so the classpath already invalidates on a bump. An
// override arrives out of band, and only reaches the compile if it is an input of its own.
val patchVersionOverride =
    providers
        .systemProperty("utrecorder.patch_version")
        .orElse(providers.environmentVariable("UTRECORDER_PATCH_VERSION"))

tasks.register("compileUscript") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Compiles every UnrealScript package of this module."

    dependsOn(tasks.withType<CompileUscriptTask>())
}

unrealScript.packages.createFromLayout(unrealScript.sourceDirectory.get())

// A task property shadows an enclosing local of the same name, so the locals a task is configured from are named
// apart from the properties they feed. Sharing a name self-assigns the property and fails as a circular reference.
unrealScript.packages.all {
    val uscriptPackage = name
    val uscriptSources = sources.convention(unrealScript.sourceDirectory.dir(uscriptPackage))
    val uscriptEditPackages = editPackages

    val compileTask =
        tasks.register<CompileUscriptTask>("compileUscript${uscriptPackage.replaceFirstChar(Char::uppercaseChar)}") {
            group = LifecycleBasePlugin.BUILD_GROUP
            description = "Compiles the $uscriptPackage UnrealScript package into $uscriptPackage.u."

            sources = uscriptSources
            packageName = uscriptPackage
            editPackages = uscriptEditPackages
            patchVersion = patchVersionOverride

            usesService(uccService)
            compilerClasspath.from(unrealScriptCompilerClasspath)
            javaLauncher = javaToolchains.launcherFor(java.toolchain)
            homeDirectory = unrealScript.homeDirectory

            outputDirectory = layout.buildDirectory.dir("uscript/$uscriptPackage")
            logDirectory = layout.buildDirectory.dir("uscript-logs/$uscriptPackage")
        }

    embeddedArtifacts.artifacts.from(compileTask.flatMap { it.outputDirectory })
}
