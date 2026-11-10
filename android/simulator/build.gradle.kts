// Desktop stand-in for the Android sender: feeds an ffmpeg H.264 test pattern
// through the real :core server so receivers can be tested without a phone.
plugins {
    kotlin("jvm")
    application
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":core")) }
application { mainClass.set("dev.omnimirror.simulator.MainKt") }
