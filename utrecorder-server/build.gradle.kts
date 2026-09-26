plugins {
    id("utrecorder.kotlin-application")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(projects.utrecorderCommon)
    implementation(projects.utrecorderCore)
    implementation(projects.utrecorderUt99)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass = "dev.klepto.utrecorder.server.UtRecorderServer"
}
