plugins {
    id("utrecorder.kotlin-library")
}

dependencies {
    implementation(libs.bundles.archives)
    implementation(libs.bundles.ktor.client)
    implementation(libs.logback.classic)
}

// loop-fs-iso-impl declares hadoop-hdfs and hadoop-common at compile scope. The ISO reader touches
// neither, and they drag in the whole Hadoop tree.
configurations.implementation {
    exclude(group = "org.apache.hadoop")
}

tasks.test {
    environment("UTRECORDER_TEST_ENV_CODEC", "libx264")
    environment("UTRECORDER_TEST_ENV_FPS", "24")
    environment("UTRECORDER_TEST_ENV_EMPTY", "")
    environment("UTRECORDER_TEST_ENV_DOTTED_KEY", "value-with-dots")
}
