import dev.klepto.utrecorder.build.lib

plugins {
    id("utrecorder.kotlin-base")
    application
}

dependencies {
    runtimeOnly(lib("logback.classic"))
}
