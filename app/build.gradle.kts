plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}

android {
    namespace = "com.rajat.sample.pdfviewer"

    compileSdk = 36

    defaultConfig {
        applicationId = "com.rajat.sample.pdfviewer"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(21)
    }

    buildTypes {
        buildTypes {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel2api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                }
                create("api27Pixel") {
                    device = "Pixel 3a"
                    apiLevel = 27
                    systemImageSource = "aosp"
                }
                create("api35Pixel") {
                    device = "Pixel 5"
                    apiLevel = 35
                    systemImageSource = "aosp"
                }
            }
            // Create a group to test across all defined devices
            groups {
                create("allApis") {
                    targetDevices.addAll(localDevices)
                }
            }
        }
    }
}


dependencies {

    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.compose.ui:ui-graphics")
    implementation(project(":pdfViewer"))

    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.material3:material3")

    // Android Studio Preview support
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Optional - Integration with activities
    implementation("androidx.activity:activity-compose:1.10.1")
}
