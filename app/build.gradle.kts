buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("org.jacoco:org.jacoco.core:0.8.12")
    }
}

import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    jacoco
}

android {
    namespace = "com.ai.controller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ai.controller"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testRuntimeOnly("org.jacoco:org.jacoco.agent:0.8.12")
    testRuntimeOnly(files("libs/jacocoagent.jar"))
}

jacoco {
    toolVersion = "0.8.12"
}

// ---- Offline Jacoco instrumentation for Robolectric ----
// Robolectric loads app classes via its SandboxClassLoader, invisible to the
// Jacoco java-agent. We bake probes into the .class files at build time.
val instrumentedClassesDir = layout.buildDirectory.dir("instrumented-classes/debug")

tasks.register("offlineInstrumentDebug") {
    dependsOn("compileDebugKotlin")
    doLast {
        val instrumenter = Instrumenter(OfflineInstrumentationAccessGenerator())
        val classesDir = file("build/tmp/kotlin-classes/debug")
        val outDir = instrumentedClassesDir.get().asFile
        outDir.deleteRecursively()
        var count = 0
        classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
            val rel = classFile.relativeTo(classesDir)
            if (rel.path.contains("databinding") || rel.name.startsWith("R$") || rel.name == "R.class"
                || rel.name == "BuildConfig.class" || rel.name == "BR.class") return@forEach
            val outFile = outDir.resolve(rel)
            outFile.parentFile.mkdirs()
            classFile.inputStream().use { inp ->
                outFile.outputStream().use { out ->
                    instrumenter.instrument(inp, out, classFile.name)
                }
            }
            count++
        }
        println("Offline instrumented " + count + " classes")
    }
}

afterEvaluate {
    tasks.findByName("testDebugUnitTest")?.let {
        it.dependsOn("offlineInstrumentDebug")
        val testTask = it as Test
        testTask.classpath = files(instrumentedClassesDir.get().asFile) + testTask.classpath
    }
}

tasks.register<JacocoReport>("jacocoOfflineInstrumentedReport") {
    jacoco.toolVersion = "0.8.12"
    dependsOn("testDebugUnitTest", "offlineInstrumentDebug")
    group = "verification"
    description = "Jacoco report using offline-instrumented classes (for Robolectric coverage)"

    val execFile = file("build/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    classDirectories.setFrom(files("build/tmp/kotlin-classes/debug"))
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(files(execFile))

    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco-offline"))
    }
}
