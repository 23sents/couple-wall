plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Relay settings are baked in at build time (CI passes them as environment variables) and can be overridden in the app.
fun buildValue(env: String, prop: String): String {
    val v = (System.getenv(env) ?: (project.findProperty(prop) as String?) ?: "").trim()
    return v.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.example.couplewall"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.couplewall"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        buildConfigField("String", "RELAY_URL", "\"${buildValue("RELAY_URL", "relayUrl")}\"")
        buildConfigField("String", "RELAY_KEY", "\"${buildValue("RELAY_KEY", "relayKey")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
