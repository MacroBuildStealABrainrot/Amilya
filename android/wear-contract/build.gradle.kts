plugins { id("com.android.library") }
android {
 namespace = "com.woundcopilot.wear.contract"
 compileSdk = 36
 defaultConfig { minSdk = 28 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation("junit:junit:4.13.2") }
