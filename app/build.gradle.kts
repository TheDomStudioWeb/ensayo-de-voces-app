plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.thedom.ensayo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.thedom.ensayo"
        minSdk = 26
        targetSdk = 37
        // En GitHub el número de versión sube solo en cada compilación
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = 100 + build
        versionName = "1.$build"
        // Solo teléfonos de 64 bits (el motor necesita mucha memoria)
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Firma propia: la misma en cada compilación, así las actualizaciones se instalan encima
    signingConfigs {
        create("ensayo") {
            storeFile = file("ensayo.keystore")
            storePassword = "ensayovoces"
            keyAlias = "ensayo"
            keyPassword = "ensayovoces"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("ensayo") }
        release { signingConfig = signingConfigs.getByName("ensayo") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.onnxruntime.android)
}
