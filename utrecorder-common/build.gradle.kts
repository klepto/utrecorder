plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.bundles.archives)
    implementation(libs.bundles.ktor.client)
}

// loop-fs-iso-impl declares hadoop-hdfs and hadoop-common at compile scope. The ISO reader touches
// neither, and they drag in the whole Hadoop tree.
configurations.implementation {
    exclude(group = "org.apache.hadoop")
}
