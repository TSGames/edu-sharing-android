import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional release signing. Provide values via `keystore.properties` (git-ignored)
// or environment variables so secrets never land in version control:
//   storeFile, storePassword, keyAlias, keyPassword
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

android {
    namespace = "org.edusharing.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.edusharing.app"
        // Android 8.0: floor for native adaptive launcher icons and a modern WebView.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Default edu-sharing server the WebView opens when none has been
        // chosen yet. Override at build time with -PeduSharingDefaultServerUrl=...
        val defaultServerUrl = (project.findProperty("eduSharingDefaultServerUrl") as String?)
            ?: "https://"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
    }

    val storeFilePath = signingValue("storeFile", "EDU_SHARING_STORE_FILE")
    if (storeFilePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingValue("storePassword", "EDU_SHARING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "EDU_SHARING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "EDU_SHARING_KEY_PASSWORD")
            }
        }
    }

    // Stable debug signing (optional). AGP auto-generates a fresh, machine-local
    // ~/.android/debug.keystore when none of this is set, which is fine for local dev but
    // means every CI runner (a new machine each time) signs debug APKs with a different key --
    // installing a newer CI build over an older one then fails with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE unless you uninstall first. Point at a checked-in-once,
    // secret-backed keystore (e.g. decoded from a CI secret) to keep the debug signature stable
    // across builds/runners. Falls back to the same "debug"/"android" values AGP's own default
    // debug keystore uses, so only the keystore file itself needs to be supplied.
    val debugStoreFilePath = signingValue("debugStoreFile", "EDU_SHARING_DEBUG_STORE_FILE")
    if (debugStoreFilePath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(debugStoreFilePath)
                storePassword =
                    signingValue("debugStorePassword", "EDU_SHARING_DEBUG_STORE_PASSWORD")
                        ?: "android"
                keyAlias = signingValue("debugKeyAlias", "EDU_SHARING_DEBUG_KEY_ALIAS")
                    ?: "androiddebugkey"
                keyPassword =
                    signingValue("debugKeyPassword", "EDU_SHARING_DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Apply the release signing config only when a keystore was supplied.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    // Android's stub android.jar throws UnsupportedOperationException from every
    // org.json.JSONObject method in plain JVM unit tests -- pull in the real implementation
    // so SharePayload.toJson() (which uses JSONObject directly) is testable here.
    testImplementation(libs.org.json)
}
