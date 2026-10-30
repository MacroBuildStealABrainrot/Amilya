plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
// OFF unless explicitly requested. Do not set this for a release without the owner's written approval.
val crashSafeRelease: String = providers.gradleProperty("wcpCrashSafeRecording").orElse("false").get().let { if (it == "true") "true" else "false" }
android {
    // Phone tests run on the SANDBOX build by default, so they can never run against the live-service debug app.
    // (Release tests still work with -PwcpTestBuildType=release, as described in android/README.md.)
    testBuildType = providers.gradleProperty("wcpTestBuildType").orElse("sandbox").get()
    namespace = "com.woundcopilot.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.woundcopilot.android"
        minSdk = 28
        targetSdk = 36
        buildConfigField("boolean", "REPAIR_SERVER", "false")
        buildConfigField("boolean", "RENDER_TEST_SERVER", "false")
        versionCode = 47
        versionName = "1.8.5-test15m"
        manifestPlaceholders["appLabel"] = "WoundCoPilot"
        buildConfigField("boolean", "WEAR_REMOTE_CONTROL", providers.gradleProperty("wcpWearRemoteControl").orElse("true").get())
        buildConfigField("boolean", "ALLOW_SCREEN_CAPTURE", providers.gradleProperty("wcpAllowScreenCapture").orElse("false").get())
        // Crash-resistant recorder: compiled into every build, switched ON only in sandbox. Turning it on for debug/release later is one
        // property (-PwcpCrashSafeRecording=true) and needs the owner's approval after the Samsung and compatibility tests pass.
        manifestPlaceholders["crashSafeRecording"] = "false"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    // TEST-ONLY signing key for the Pilot app, so every new Pilot build can update the last one in place
    // (CI used to make a new random key each run). Never used for the release app.
    val pilotKeystore = rootProject.file("../ci/pilot-test.keystore")
    signingConfigs {
        if (pilotKeystore.exists()) create("pilotTest") {
            storeFile = pilotKeystore; storePassword = "pilottest"; keyAlias = "pilottest"; keyPassword = "pilottest"
        }
    }
    buildTypes {
        // Normal debug and release builds keep talking to the existing service, exactly as before.
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"https://woundcopilot-1039151887397.us-central1.run.app/api/v1\"")
            buildConfigField("boolean", "SANDBOX", "false")
            buildConfigField("boolean", "CRASH_SAFE_RECORDING", crashSafeRelease)
            manifestPlaceholders["crashSafeRecording"] = crashSafeRelease
            buildConfigField("boolean", "RETAIN_UNRESOLVED", "false")
        }
        getByName("release") {
            applicationIdSuffix = ".release"
            isDebuggable = false
            buildConfigField("String", "API_BASE_URL", "\"https://woundcopilot-1039151887397.us-central1.run.app/api/v1\"")
            buildConfigField("boolean", "SANDBOX", "false")
            buildConfigField("boolean", "CRASH_SAFE_RECORDING", crashSafeRelease)
            manifestPlaceholders["crashSafeRecording"] = crashSafeRelease
            buildConfigField("boolean", "RETAIN_UNRESOLVED", "false")
        }
        // Side-by-side PILOT candidate: real HTTPS API, separate app data and identity.
        // Uses the proven original recorder until the crash-safe recorder passes device validation.
        // Do not use identifiable clinical data until compatibility and privacy checks are completed.
        create("pilot") {
            initWith(getByName("debug"))
            // ".pilot2": new package so it installs BESIDE the older Pilot (different signing key) without touching its data.
            applicationIdSuffix = ".pilot2"
            versionNameSuffix = "-pilot"
            manifestPlaceholders["appLabel"] = "Amilya Test 15"
            if (pilotKeystore.exists()) signingConfig = signingConfigs.getByName("pilotTest")
            buildConfigField("String", "API_BASE_URL", "\"https://woundcopilot-1039151887397.us-central1.run.app/api/v1\"")
            buildConfigField("boolean", "SANDBOX", "false")
            buildConfigField("boolean", "CRASH_SAFE_RECORDING", "false")
            manifestPlaceholders["crashSafeRecording"] = "false"
            buildConfigField("boolean", "RETAIN_UNRESOLVED", "false")
            matchingFallbacks += listOf("debug")
        }
        // Isolated repair verification app: never replaces a recording-bearing install.
        create("repair") {
            initWith(getByName("pilot"))
            // ".repair2": installs beside any older repair app (different signing key), never over it.
            applicationIdSuffix = ".repair2"
            buildConfigField("boolean", "REPAIR_SERVER", "true")
            versionNameSuffix = "-server-test"
            manifestPlaceholders["appLabel"] = "Amilya Server Test"
            buildConfigField("String", "API_BASE_URL", "\"https://repair---woundcopilot-rhd7rrcqdq-uc.a.run.app/api/v1\"")
            matchingFallbacks += listOf("pilot", "debug")
        }
        // A new package keeps all existing apps, sessions and recordings separate.
        create("renderTest") {
            initWith(getByName("pilot"))
            applicationIdSuffix = ".rendertest"
            versionNameSuffix = "-render-test14"
            buildConfigField("boolean", "RENDER_TEST_SERVER", "true")
            buildConfigField("boolean", "REPAIR_SERVER", "false")
            buildConfigField("boolean", "WEAR_REMOTE_CONTROL", "false")
            buildConfigField("String", "API_BASE_URL", "\"https://amilya-repair-test.onrender.com/api/v1\"")
            matchingFallbacks += listOf("pilot", "debug")
        }
        // Test-only build. Its own app id (com.woundcopilot.android.sandbox) so it installs beside, never over,
        // your other builds. It can only reach the fake test server on this PC (through adb reverse). The
        // production address is refused in code, so a recording can never be sent to the live service.
        create("sandbox") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".sandbox"
            versionNameSuffix = "-sandbox"
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:18766/api/v1\"")
            buildConfigField("boolean", "SANDBOX", "true")
            buildConfigField("boolean", "CRASH_SAFE_RECORDING", "true")
            manifestPlaceholders["crashSafeRecording"] = "true"
            buildConfigField("boolean", "RETAIN_UNRESOLVED", "true")
            matchingFallbacks += listOf("debug")
        }
    }
}
dependencies {
    implementation(project(":wear-contract"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add("sandboxImplementation", "androidx.compose.ui:ui-tooling")
    add("sandboxImplementation", "androidx.compose.ui:ui-test-manifest")
}


// Optional CI-only bounded-disk fixture; never used by the application runtime.
tasks.withType<Test>().configureEach {
    providers.environmentVariable("WCP_STORAGE_FULL_TEST_DIR").orNull?.let {
        systemProperty("full.dir", it)
    }
}
