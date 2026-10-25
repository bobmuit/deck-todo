plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)
    api(libs.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(project(":domain"))
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform {
        // Tests against the real server: ./gradlew :deck-api:test -Plive
        if (!project.hasProperty("live")) excludeTags("live")
    }
    if (project.hasProperty("live")) outputs.upToDateWhen { false }
}
