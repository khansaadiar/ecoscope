plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.wasteclassifier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wasteclassifier"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packagingOptions {
        doNotStrip("**/*.tflite")
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }

    aaptOptions {
        noCompress("sfb", "glb") // Untuk 3D assets
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

//    // CameraX dependencies
//    implementation("androidx.camera:camera-core:1.3.0")
//    implementation("androidx.camera:camera-camera2:1.3.0")
//    implementation("androidx.camera:camera-lifecycle:1.3.0")
//    implementation("androidx.camera:camera-view:1.3.0")
//    implementation("androidx.camera:camera-extensions:1.2.0")
//
//    // TensorFlow Lite
//    implementation("org.tensorflow:tensorflow-lite:2.9.0")
//    implementation("org.tensorflow:tensorflow-lite-gpu:2.9.0")
//
//    // ARCore
//    implementation("com.google.ar:core:1.39.0")
//
//    // Sceneform libraries
//    implementation("io.github.sceneview:arsceneview:1.2.2")
//    implementation("io.github.sceneview:sceneview:1.2.2")
//
//    // For 3D model rendering
//    implementation("com.github.andrefrsousa:SuperBottomSheet:1.5.0")
//
//    // Testing libraries
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.ext.junit)
//    androidTestImplementation(libs.espresso.core)
//
//    // Material Design
//    implementation("com.google.android.material:material:1.11.0")
    implementation ("androidx.camera:camera-core:1.3.0")
    implementation ("androidx.camera:camera-camera2:1.3.0")
    implementation ("androidx.camera:camera-lifecycle:1.3.0")
    implementation ("androidx.camera:camera-view:1.3.0")
    implementation ("org.tensorflow:tensorflow-lite:2.9.0")

}
