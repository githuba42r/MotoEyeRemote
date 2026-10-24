import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Version comes from version.properties at the project root (written by the
// release pipeline); fall back to the last known values for old checkouts
// that predate the file.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.isFile) {
        versionPropsFile.inputStream().use { this.load(it) }
    }
}
val releaseVersionCode = if (versionPropsFile.isFile) {
    versionProps.getProperty("VERSION_CODE").toInt()
} else {
    52 // fallback: last hardcoded versionCode, for old checkouts without version.properties
}
val releaseVersionName = if (versionPropsFile.isFile) {
    "${
        listOf("VERSION_MAJOR", "VERSION_MINOR", "VERSION_PATCH")
            .joinToString(".") { p -> versionProps.getProperty(p) }
    }"
} else {
    "0.5.8" // fallback: last hardcoded versionName
}

// CI keystore: the workflow exports these env vars when a release keystore
// secret is present; without them the release build stays unsigned.
val ciKeystoreBase64 = System.getenv("KEYSTORE_BASE64")
val ciKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("KEY_ALIAS")
val ciKeyPassword = System.getenv("KEY_PASSWORD")
val hasCiKeystore =
    !ciKeystoreBase64.isNullOrBlank() &&
        !ciKeystorePassword.isNullOrBlank() &&
        !ciKeyAlias.isNullOrBlank() &&
        !ciKeyPassword.isNullOrBlank()

android {
    namespace = "com.motoeye.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.a42r.motoeye.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        if (hasCiKeystore) {
            create("ciRelease") {
                val keystoreFile = File(
                    System.getenv("RUNNER_TEMP")
                        ?: layout.buildDirectory.dir("ci").get().asFile.path,
                    "motoeye-release.keystore"
                )
                keystoreFile.parentFile.mkdirs()
                Base64.getDecoder().decode(
                    ciKeystoreBase64!!.replace(Regex("\\s"), "")
                ).let { decoded -> keystoreFile.writeBytes(decoded) }
                storeFile = keystoreFile
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasCiKeystore) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
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
        compose = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media3 for media session
    implementation("androidx.media3:media3-session:1.5.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
