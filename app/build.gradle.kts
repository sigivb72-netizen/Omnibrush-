plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val dataRepo = providers.gradleProperty("dataRepo").get()
val dataBranch = providers.gradleProperty("dataBranch").get()

// Bundle the repo's games.json as an offline seed so first launch works without network.
val seedAssets = layout.buildDirectory.dir("generated/seedAssets")
val copySeed = tasks.register<Copy>("copySeedData") {
    from(rootProject.file("data")) { include("games.json", "news.json") }
    into(seedAssets)
}

android {
    namespace = "app.mygachalist"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mygachalist"
        minSdk = 26
        targetSdk = 35
        versionCode = (providers.gradleProperty("buildNumber").orNull ?: "1").toInt()
        versionName = "1.0." + (providers.gradleProperty("buildNumber").orNull ?: "0")
        buildConfigField(
            "String", "DATA_BASE_URL",
            "\"https://raw.githubusercontent.com/$dataRepo/$dataBranch/data/\""
        )
        buildConfigField("String", "REPO_URL", "\"https://github.com/$dataRepo\"")
    }

    signingConfigs {
        // Fixed debug key committed to the repo so every CI build can be
        // installed over the previous one without losing your list.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].assets.srcDir(seedAssets)
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.path)
                it.maxHeapSize = "1536m"
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(copySeed) }

ksp { arg("room.generateKotlin", "true") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
