plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("app.vinkje.importer.MainKt")
    applicationName = "deck-todo-import"
}

dependencies {
    implementation(project(":deck-api"))
    implementation(project(":domain"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform {
        // The rehearsal against the real server: ./gradlew :importer:test -Plive
        if (!project.hasProperty("live")) excludeTags("live")
    }
    if (project.hasProperty("live")) outputs.upToDateWhen { false }
}
