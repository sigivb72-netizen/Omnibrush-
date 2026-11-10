// Pure-JVM module: wire protocol, pairing, HTTP/WebSocket server and the
// receiver fan-out. No Android APIs, so all of it is unit-tested on a desktop JVM.
plugins {
    kotlin("jvm")
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
