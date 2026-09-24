plugins {
    id("utrecorder.kotlin-application")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(projects.utrecorderCommon)
    implementation(projects.utrecorderCore)
}

application {
    mainClass = "dev.klepto.utrecorder.cli.UtRecorder"
}
