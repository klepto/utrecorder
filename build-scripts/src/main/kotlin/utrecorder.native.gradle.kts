import dev.klepto.utrecorder.build.createFromLayout
import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import dev.klepto.utrecorder.build.native.CompileNativeTask
import dev.klepto.utrecorder.build.native.NativeBinaryType
import dev.klepto.utrecorder.build.native.NativeExtension
import dev.klepto.utrecorder.build.native.NativePlatform
import dev.klepto.utrecorder.build.native.NativeToolchain
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    `java-library`
    id("utrecorder.embedded-artifacts")
}

val embeddedArtifacts = extensions.getByType<EmbeddedArtifactsExtension>()
embeddedArtifacts.namespace.convention("native")

val nativeBinaries = extensions.create<NativeExtension>("nativeBinaries")
nativeBinaries.sourceDirectory.convention(layout.projectDirectory.dir("src/main/native"))

/**
 * The platform this build targets. Nothing here cross-compiles, so it is the platform the build runs on.
 */
val targetPlatform = NativePlatform.current

val nativeToolchain =
    providers.of(NativeToolchain.Source::class.java) {
        parameters.platform = targetPlatform.name
    }

// Per-platform classifier, because the jar holds only the binaries of the platform that built it. A consumer that
// needs another platform's binaries resolves that platform's jar rather than a different entry in this one.
tasks.jar {
    archiveClassifier = targetPlatform.id
}

tasks.register("compileNative") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Compiles every native binary of this module."

    dependsOn(tasks.withType<CompileNativeTask>())
}

nativeBinaries.binaries.createFromLayout(nativeBinaries.sourceDirectory.get())

// A task property shadows an enclosing local of the same name, so the locals a task is configured from are named apart
// from the properties they feed. Sharing a name self-assigns the property and fails as a circular reference.
nativeBinaries.binaries.all {
    val nativeName = name
    val nativeSources = sources.convention(nativeBinaries.sourceDirectory.dir(nativeName))
    val nativeType = type.convention(NativeBinaryType.SHARED_LIBRARY)
    val nativePlatforms = platforms.convention(NativePlatform.entries.toSet())

    val compileTask =
        tasks.register<CompileNativeTask>("compileNative${nativeName.replaceFirstChar(Char::uppercaseChar)}") {
            group = LifecycleBasePlugin.BUILD_GROUP
            description = "Compiles the $nativeName native binary."

            sources = nativeSources
            binaryName = nativeName
            binaryType = nativeType
            platforms = nativePlatforms

            platform = targetPlatform
            toolchain = nativeToolchain

            outputDirectory = layout.buildDirectory.dir("native/$nativeName")
            logDirectory = layout.buildDirectory.dir("native-logs/$nativeName")
            workDirectory = layout.buildDirectory.dir("tmp/native/$nativeName")
        }

    embeddedArtifacts.artifacts.from(compileTask.flatMap { it.outputDirectory })
}
