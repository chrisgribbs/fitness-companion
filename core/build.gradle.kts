// Pure Kotlin module: calculations with no Android dependency, so they are fast to unit test.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    testLogging { events("passed", "failed"); showStandardStreams = false }
}

dependencies {
    testImplementation(libs.junit)
}
