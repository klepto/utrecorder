plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    implementation(projects.utrecorderCommon)
    implementation(projects.utrecorderUt99)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("linux-x86_64") })
    runtimeOnly(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.javacpp) { classifier("linux-x86_64") })
}
