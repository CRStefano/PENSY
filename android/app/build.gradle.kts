import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key, never kept in the repository. Taken from, in order:
//   1. environment variables PENSY_KEYSTORE (path to the .keystore file), PENSY_KEYSTORE_PASSWORD,
//      PENSY_KEY_ALIAS, PENSY_KEY_PASSWORD (this is how the GitHub workflow signs);
//   2. android/keystore.properties with storeFile, storePassword, keyAlias, keyPassword
//      (for building on your own computer; the file is in .gitignore).
// With neither, the release build is signed with the debug key: it installs, but not as an
// update of a copy signed with the real key.
val signing: Map<String, String>? = run {
    val env = System.getenv()
    val file = rootProject.file("keystore.properties")
    when {
        !env["PENSY_KEYSTORE"].isNullOrBlank() -> mapOf(
            "storeFile" to env.getValue("PENSY_KEYSTORE"),
            "storePassword" to (env["PENSY_KEYSTORE_PASSWORD"] ?: ""),
            "keyAlias" to (env["PENSY_KEY_ALIAS"] ?: "pensy"),
            "keyPassword" to (env["PENSY_KEY_PASSWORD"] ?: env["PENSY_KEYSTORE_PASSWORD"] ?: "")
        )
        file.exists() -> Properties().apply { file.inputStream().use { load(it) } }
            .let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
        else -> null
    }
}

android {
    namespace = "app.pensy"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pensy"
        minSdk = 26          // Android 8.0: java.time, PBKDF2-SHA256, notification channels
        targetSdk = 35
        versionCode = 11
        versionName = "0.8"
    }

    signingConfigs {
        signing?.let { key ->
            create("release") {
                storeFile = rootProject.file(key.getValue("storeFile"))
                storePassword = key["storePassword"]
                keyAlias = key["keyAlias"]
                keyPassword = key["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            // No shrinking: the release is the same code as the tested build (the app is small anyway,
            // most of the APK is the text reader and its models). proguard-rules.pro is ready if needed.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            // The pure-Kotlin core lives outside the Android project, shared and testable on its own.
            // jniLibs/ holds the prebuilt text reader (tools/build-ocr.sh), assets/ its Italian model.
            java.srcDirs("src/main/java", "../../core/src")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // QR codes and barcodes: read and redraw. Pure Java, offline. The only dependency.
    implementation("com.google.zxing:core:3.5.3")
}
