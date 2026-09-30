plugins {
    id("utrecorder.kotlin-application")
    id("utrecorder.kotlin-serialization")
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
    runtimeOnly(projects.utrecorderWeb)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass = "dev.klepto.utrecorder.server.Server"
}
