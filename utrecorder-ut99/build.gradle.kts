plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(projects.utrecorderCommon)
    testImplementation(libs.commons.compress)
}
