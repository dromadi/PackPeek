import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val signingFile = rootProject.file("keystore.properties")
val signing =
    Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

val robolectricRuntime: Configuration by configurations.creating

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("robolectric.graphicsMode", "NATIVE")
            // Robolectric's Android framework jar comes through Gradle (cached, mirrored) instead
            // of Robolectric's own downloader, so screen renders also work offline.
            test.inputs.files(robolectricRuntime)
            test.doFirst {
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", robolectricRuntime.singleFile.parentFile.absolutePath)
            }
        }
    }
    namespace = "com.packpeek.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.packpeek.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 20016
        versionName = "2.0.0-alpha.16"
        buildConfigField("boolean", "SHOW_RATE", "false")
        buildConfigField("String", "SUPPORT_EMAIL", "\"\"")
    }
    signingConfigs {
        if (signingFile.exists())
            create("owner") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("owner")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes +=
            setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

// ARSCLib ships desktop copies of android.* and org.xmlpull.* classes. Android provides the real
// ones, and R8 would otherwise optimise app code against the copies (AbstractMethodError at
// startup). Its framework tables are only needed for decoding, which merging does not do.
val arscLib by configurations.creating
val filteredArscLib =
    tasks.register<Jar>("filteredArscLib") {
        archiveFileName.set("arsclib-filtered.jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        from({ arscLib.map { zipTree(it) } }) {
            exclude("android/**", "org/xmlpull/**", "frameworks/**")
        }
    }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.android.tools.build:apksig:8.13.2")
    arscLib("io.github.reandroid:ARSCLib:1.4.0")
    implementation(files(filteredArscLib))
    testImplementation("junit:junit:4.13.2")
    // Screen renders on the JVM (Robolectric native graphics): checks the UI without an emulator.
    testImplementation("org.robolectric:robolectric:4.17")
    // Framework jar for @Config(sdk = [35]) with Robolectric 4.17.
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7") { isTransitive = false }
    testImplementation(platform("androidx.compose:compose-bom:2025.12.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
