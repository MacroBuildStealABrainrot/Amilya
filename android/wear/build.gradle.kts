plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.woundcopilot.wear"
 compileSdk = 36
 defaultConfig {
  applicationId = "com.woundcopilot.android"
  minSdk = 30
  targetSdk = 36
  versionCode = 30
  versionName = "1.8.5-test10"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true; buildConfig = true }
 buildTypes {
  getByName("release") { applicationIdSuffix = ".release"; isDebuggable=false }
  // Test-only watch build that pairs with the phone "sandbox" build (same package name, as the Data Layer requires).
  // It contains no server address and never talks to the internet itself.
  create("sandbox") { initWith(getByName("debug")); applicationIdSuffix = ".sandbox"; versionNameSuffix = "-sandbox"; matchingFallbacks += listOf("debug") }
 }
}
dependencies {
 implementation(project(":wear-contract"))
 implementation(platform("androidx.compose:compose-bom:2026.02.01"))
 implementation("androidx.activity:activity-compose:1.8.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
 implementation("androidx.wear.compose:compose-material3:1.6.2")
 implementation("androidx.wear:wear-remote-interactions:1.1.0")
 implementation("com.google.android.gms:play-services-wearable:20.0.1")
 testImplementation("junit:junit:4.13.2")
}
