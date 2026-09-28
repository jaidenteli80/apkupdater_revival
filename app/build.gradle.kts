import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.apkupdater"
    compileSdk = 37

    val buildNumber = System.getenv("BUILD_NUMBER").orEmpty()
    defaultConfig {
        applicationId = "com.apkupdater.revived" + System.getenv("BUILD_TAG").orEmpty()
        minSdk = 24
        targetSdk = 36
        versionCode = if (buildNumber.isEmpty()) 200 else buildNumber.toInt()
        versionName = if (buildNumber.isEmpty()) "2.0.0" else "0.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            try {
                val props = Properties()
                props.load(FileInputStream(file("../local.properties")))
                storeFile = file(props.getProperty("keystore.file"))
                storePassword = props.getProperty("keystore.password")
                keyAlias = props.getProperty("keystore.keyalias")
                keyPassword = props.getProperty("keystore.keypassword")
            } catch (_: Exception) {
                val config = signingConfigs.getByName("debug")
                storeFile = config.storeFile
                storePassword = config.storePassword
                keyAlias = config.keyAlias
                keyPassword = config.keyPassword
                println("Signing config not found. Using debug settings.")
            }
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warning.addAll(arrayOf("ExtraTranslation", "MissingTranslation", "MissingQuantity"))
        disable.add("OldTargetApi")
    }
}

dependencies {

    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.navigation:navigation-runtime-ktx:2.10.2")
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs-gson:0.4.3") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions")
    }
    implementation("com.github.rumboalla.KryptoPrefs:kryptoprefs:0.4.3")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.auroraoss:gplayapi:3.6.4")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.github.g00fy2:versioncompare:1.5.0")
    implementation("io.insert-koin:koin-android:4.2.2")
    implementation("io.insert-koin:koin-androidx-compose:4.2.2")
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")

}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { _ ->
            tasks.configureEach {
                if (name == "package${variant.name.replaceFirstChar { it.uppercase() }}") {
                    doLast {
                        outputs.files.files
                            .flatMap { it.walkTopDown().filter { f -> f.extension == "apk" }.toList() }
                            .forEach { apk -> runCatching { apk.copyTo(File(apk.parentFile, "${android.defaultConfig.applicationId}-${variant.buildType}.apk"), true) }.getOrNull() }
                    }
                }
            }
        }
    }
}
