plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firma opcional con tu propia clave (ver README). Sin ella se firma con la clave de depuración,
// que en GitHub Actions cambia en cada compilación: para actualizar habría que desinstalar antes.
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE")

android {
    namespace = "io.github.plexactivitytracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.plexactivitytracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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

    lint {
        // Que un aviso de lint no tumbe la compilación en GitHub Actions
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(project(":api"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
