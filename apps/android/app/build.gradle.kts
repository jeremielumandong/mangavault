import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Build-time settings. The real API domain must never be committed, so it is
// looked up in this order: -P Gradle property, environment variable, the
// gitignored config.env at the repository root, then a placeholder.
fun configValue(property: String, envName: String): String? {
    (findProperty(property) as String?)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val file = rootProject.file("../../config.env")
    if (file.isFile) {
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.startsWith("#") || !line.contains('=')) continue
            if (line.substringBefore('=').trim() != envName) continue
            val value = line.substringAfter('=').trim().trim('"', '\'')
            if (value.isNotEmpty()) return value
        }
    }
    return null
}

val apiDomain = (configValue("apiDomain", "API_DOMAIN") ?: "api.example.com")
    .removePrefix("https://").removePrefix("http://").trimEnd('/')
val googleWebClientId = configValue("googleWebClientId", "GOOGLE_WEB_CLIENT_ID") ?: ""
// AUTH_MODE=open: no sign-in; the app uses the backend's single owner account.
val openAccount = configValue("authMode", "AUTH_MODE") == "open"

android {
    namespace = "dev.mangavault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mangavault.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "API_BASE_URL", "\"https://$apiDomain/v1\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("boolean", "OPEN_ACCOUNT", "$openAccount")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_SIGN_IN", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "DEV_SIGN_IN", "false")
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

    lint {
        // Reported in CI as an artifact; not a gate yet.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    testImplementation(libs.junit)
}
