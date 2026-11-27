import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.wanderlust.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wanderlust.app"
        minSdk = 26
        targetSdk = 36
        // CI passes these from the git tag, e.g. -PversionName=1.2.0 -PversionCode=12
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        // GitHub repo whose Releases the in-app updater checks
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: "VivekMuralee07/Wanderlust"}\"")
    }

    // Release signing: from environment variables (CI) or a git-ignored keystore.properties file (local).
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun secret(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
    val storePath = secret("WANDERLUST_KEYSTORE", "storeFile")
    signingConfigs {
        if (storePath != null) create("release") {
            storeFile = rootProject.file(storePath)
            storePassword = secret("WANDERLUST_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("WANDERLUST_KEY_ALIAS", "keyAlias")
            keyPassword = secret("WANDERLUST_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        debug {
            // lets a debug build live next to the release app; in-app updates are off in debug builds
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
            }
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Official Anthropic SDK (Java SDK, used from Kotlin) for the AI travel agent
    implementation("com.anthropic:anthropic-java:3.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Google Play In-App Updates (used when the app was installed from Play)
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
