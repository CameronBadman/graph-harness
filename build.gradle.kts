plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "dev.graphharness"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "graphharness.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
