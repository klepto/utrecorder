plugins {
    id("utrecorder.kotlin-application")
}

dependencies {
    implementation(projects.utrecorderCommon)
    implementation(projects.utrecorderCore)
}

application {
    mainClass = "dev.klepto.utrecorder.cli.UtRecorderCli"
}
