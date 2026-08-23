import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM so the format work can be exercised on the desktop, and Android-free so the
// same classes run unchanged inside the app.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(libs.junit)
}

/** Desktop harness: renders a PSD/PSB to PNGs for verification without a device. */
tasks.register<JavaExec>("renderPsd") {
    group = "verification"
    description = "Parses a PSD/PSB and writes preview PNGs. Usage: -Ppsd=<path> [-Pout=<dir>]"
    mainClass.set("com.psmobile.psd.tools.RenderCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")

    val psdPath = providers.gradleProperty("psd")
    val outPath = providers.gradleProperty("out")
        .orElse(layout.buildDirectory.dir("psd-out").map { it.asFile.absolutePath })
    argumentProviders.add {
        val input = psdPath.orNull ?: throw GradleException("Pass -Ppsd=<path to .psd/.psb>")
        listOf(input, outPath.get())
    }
}
