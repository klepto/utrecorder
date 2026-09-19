plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(projects.utrecorderCommon)
}
