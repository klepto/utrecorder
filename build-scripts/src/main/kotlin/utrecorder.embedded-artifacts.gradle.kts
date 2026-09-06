import dev.klepto.utrecorder.build.embedded.EMBEDDED_ARTIFACT_INDEX
import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactIndexTask
import dev.klepto.utrecorder.build.embedded.EmbeddedArtifactsExtension
import dev.klepto.utrecorder.build.javaVersion
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    `java-library`
}

val embeddedArtifacts = extensions.create<EmbeddedArtifactsExtension>("embeddedArtifacts")
embeddedArtifacts.namespace.convention("artifacts")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

val embeddedArtifactIndex =
    tasks.register<EmbeddedArtifactIndexTask>("embeddedArtifactIndex") {
        group = LifecycleBasePlugin.BUILD_GROUP
        description = "Writes the index naming the artifacts this module embeds in its jar."

        artifacts.from(embeddedArtifacts.artifacts)
        index = layout.buildDirectory.file("embedded-artifacts/$EMBEDDED_ARTIFACT_INDEX")
    }

// `into` resolves the provider, so the namespace stays lazy and a module can still override it after the plugin
// applies.
tasks.processResources {
    from(embeddedArtifacts.artifacts) { into(embeddedArtifacts.namespace) }
    from(embeddedArtifactIndex) { into(embeddedArtifacts.namespace) }
}

// The jar is the invalidation signal every consumer sees. Without this its entries carry the time of the build, so an
// unchanged rebuild would produce a different jar and rebuild everything downstream.
tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
