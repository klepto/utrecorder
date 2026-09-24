plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.archives)
    implementation(libs.bundles.ktor.client)
}

configurations.implementation {
    exclude(group = "org.apache.hadoop")
}
