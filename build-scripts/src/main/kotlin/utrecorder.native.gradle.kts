import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import dev.klepto.utrecorder.build.native.CompileNativeTask
import dev.klepto.utrecorder.build.native.NativeBinaryType
import dev.klepto.utrecorder.build.native.NativeExtension
import dev.klepto.utrecorder.build.native.NativePlatform
import dev.klepto.utrecorder.build.native.NativeToolchain
import dev.klepto.utrecorder.build.registerLayoutTasks

plugins {
    `java-library`
    id("utrecorder.embedded-artifacts")
}

val embeddedArtifacts = extensions.getByType<EmbeddedArtifactsExtension>()
embeddedArtifacts.namespace.convention("native")

val nativeBinaries = extensions.create<NativeExtension>("nativeBinaries")
nativeBinaries.sourceDirectory.convention(layout.projectDirectory.dir("src/main/native"))

val targetPlatform = NativePlatform.current

val nativeToolchain =
    providers.of(NativeToolchain.Source::class.java) {
        parameters.platform = targetPlatform.name
    }

tasks.jar {
    archiveClassifier = targetPlatform.id
}

registerLayoutTasks(
    elements = nativeBinaries.binaries,
    sourceDirectory = nativeBinaries.sourceDirectory.get(),
    taskName = "compileNative",
    description = "Compiles every native binary of this module.",
    taskType = CompileNativeTask::class,
    outputDirectory = { it.outputDirectory },
) { binary ->
    description = "Compiles the ${binary.name} native binary."

    sources = binary.sources.convention(nativeBinaries.sourceDirectory.dir(binary.name))
    binaryName = binary.name
    binaryType = binary.type.convention(NativeBinaryType.SHARED_LIBRARY)
    platforms = binary.platforms.convention(NativePlatform.entries.toSet())

    platform = targetPlatform
    toolchain = nativeToolchain

    outputDirectory = layout.buildDirectory.dir("native/${binary.name}")
    logDirectory = layout.buildDirectory.dir("native-logs/${binary.name}")
    workDirectory = layout.buildDirectory.dir("tmp/native/${binary.name}")
}
