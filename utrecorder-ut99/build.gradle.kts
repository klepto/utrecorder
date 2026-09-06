plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    implementation(projects.utrecorderCommon)
    implementation(libs.logback.classic)
}
