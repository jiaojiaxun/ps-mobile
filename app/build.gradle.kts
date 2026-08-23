import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The released version, and the integer the in-app updater compares against.
 *
 * CI passes the git tag through `APP_VERSION_NAME`; a local build falls back to the property in
 * `gradle.properties`. Deriving the code from the name keeps the two in step, so a build made
 * here and one made by the workflow agree on which is newer.
 */
val appVersionName: String = (
    providers.environmentVariable("APP_VERSION_NAME").orNull
        ?: providers.gradleProperty("appVersionName").orNull
        ?: "0.1.0"
    ).removePrefix("v")

val appVersionCode: Int = run {
    val numbers = appVersionName.substringBefore('-').split('.')
    val major = numbers.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = numbers.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = numbers.getOrNull(2)?.toIntOrNull() ?: 0
    major * 10_000 + minor * 100 + patch
}

/**
 * Release signing material, from the developer's machine or from CI.
 *
 * The keystore never enters the repository: locally it is read from
 * `~/.psmobile/keystore.properties`, and in CI the workflow writes the same values out of
 * repository secrets. When neither is present the release build falls back to the debug key so
 * that a fresh clone still builds — but such a build is signed differently and therefore cannot
 * update an installation made from an official release.
 */
val signingProperties: Properties? = run {
    val explicit = providers.environmentVariable("SIGNING_KEYSTORE_PROPERTIES").orNull
    val candidate = if (explicit != null) {
        File(explicit)
    } else {
        File(System.getProperty("user.home"), ".psmobile/keystore.properties")
    }
    if (!candidate.isFile) return@run null
    Properties().apply { candidate.inputStream().use { load(it) } }
        .takeIf { properties ->
            val store = properties.getProperty("storeFile")
            store != null && File(store).isFile
        }
}

android {
    namespace = "com.psmobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.psmobile"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GITHUB_OWNER", "\"JustRin\"")
        buildConfigField("String", "GITHUB_REPO", "\"ps-mobile\"")
    }

    signingConfigs {
        if (signingProperties != null) {
            create("release") {
                storeFile = File(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // One APK per release, named so the updater can recognise it among the release assets.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (buildType.name == "release") {
                output.outputFileName = "ps-mobile-$appVersionName.apk"
            }
        }
    }
}

dependencies {
    implementation(project(":psdcore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)
}
