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
}
