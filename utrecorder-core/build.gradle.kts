plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(projects.utrecorderCommon)
    implementation(projects.utrecorderUt99)
    implementation(libs.ffmpeg)
    implementation(libs.ktor.client.core)
    runtimeOnly(projects.utrecorderHooks)
    runtimeOnly(projects.utrecorderUscript)
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.javacpp) { classifier("linux-x86_64") })
}
