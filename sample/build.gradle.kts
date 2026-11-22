plugins {
    id("com.android.application")
}

android {
    namespace = "dev.cuculus.update.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.cuculus.update.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "1.3.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":updater"))
    implementation(libs.kotlinx.coroutines.android)
}
