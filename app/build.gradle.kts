import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Hosts to offer on first launch, as "Label=address,Label=address". Kept out of git in
// local.properties (rex.defaultHosts=...) so the repo carries no tailnet names. A build can name its
// own with -Prex.defaultHosts=..., such as the demo build in docs/demo, pointed at a local demo server.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val defaultHosts: String = (findProperty("rex.defaultHosts") as String?) ?: localProps.getProperty("rex.defaultHosts", "")

// Where release builds find their updates: the GitHub releases of rex.updateRepo. While that repository
// is private, rex.updateToken is a fine-grained token that can only read it, also kept in local.properties.
val updateRepo: String = localProps.getProperty("rex.updateRepo", "jeffarese/rex-android-releases")
val updateToken: String = localProps.getProperty("rex.updateToken", "")

// Release signing comes from keystore.properties (also untracked); without it, release builds use
// the debug key so a sideloadable APK can still be produced.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.jeffarese.rexmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jeffarese.rexmobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 34
        versionName = "0.3.12"
        buildConfigField("String", "DEFAULT_HOSTS", "\"${defaultHosts.replace("\"", "")}\"")
        // Only release builds update themselves: others are signed with another key, so couldn't.
        buildConfigField("String", "UPDATE_REPO", "\"\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The terminal engine, libghostty-vt, is built for 64-bit ARM only (scripts/build-libghostty.sh).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            if (signingConfigs.findByName("release") != null) {
                buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
                buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
            }
        }
        // For the instrumented engine benchmarks: like debug, but not debuggable, so ART compiles the
        // engine as it does in a release. See docs/perf/README.md.
        create("benchmark") {
            initWith(getByName("debug"))
            isDebuggable = false
            // The release key where there is one, so it installs over a release build.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    testBuildType = "benchmark"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Fonts stored as they are load straight from the APK, where deflated ones are inflated into memory first.
    androidResources { noCompress += "ttf" }
    testOptions {
        // The emulator core touches android.util/graphics only for logging and color parsing.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // The terminal engine built for this Mac (scripts/build-libghostty.sh).
            test.systemProperty("java.library.path", rootProject.file("terminal/native/host").path)
            listOf("REX_LIVE_HOST", "REX_LIVE_READONLY_HOST").forEach { name ->
                System.getenv(name)?.let { test.environment(name, it) }
            }
        }
    }
}

// The benchmark build type is for instrumented benchmarks only: no unit test run of its own.
androidComponents {
    beforeVariants(selector().withBuildType("benchmark")) { it.enableUnitTest = false }
}

// The font's and the terminal engine's licenses ship inside the app, for Settings' open-source licenses page.
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicenses by tasks.registering(Copy::class) {
    from("FONT-LICENSE-OFL.txt")
    from(rootProject.file("terminal/native/ENGINE-LICENSES.txt"))
    into(licenseAssets.map { it.dir("licenses") })
}
android.sourceSets.getByName("main").assets.srcDir(licenseAssets)
tasks.named("preBuild") { dependsOn(copyLicenses) }

dependencies {
    implementation(project(":terminal"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Scans a pairing code without the camera permission: Play services draws the scanner.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
