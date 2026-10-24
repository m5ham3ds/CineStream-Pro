import groovy.json.JsonSlurper
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  id("org.jetbrains.kotlin.android")
  alias(libs.plugins.android.application)
  
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 36
  buildToolsVersion = "36.0.0"

  defaultConfig {
    applicationId = "com.aistudio.cinestream.xyzabc"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += listOf(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    )
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.maxHeapSize = "2048m"
        it.jvmArgs("-XX:MaxMetaspaceSize=1024m")
      }
    }
  }
  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Resolve the canonical Web OAuth Client ID from the official Google Services file
// for this exact Android application. Environment placeholders must never override it.
val googleServicesFile = rootProject.file("app/google-services.json")
if (!googleServicesFile.isFile) {
  throw GradleException("Missing app/google-services.json. Restore the official Firebase configuration before building.")
}
val googleServicesJson = JsonSlurper().parse(googleServicesFile) as? Map<*, *>
  ?: throw GradleException("app/google-services.json is not a valid JSON object.")
val firebaseClients = googleServicesJson["client"] as? List<*> ?: emptyList<Any>()
val appIdForOAuth = android.defaultConfig.applicationId
val matchingFirebaseClient = firebaseClients
  .mapNotNull { it as? Map<*, *> }
  .firstOrNull { client ->
    val clientInfo = client["client_info"] as? Map<*, *>
    val androidInfo = clientInfo?.get("android_client_info") as? Map<*, *>
    androidInfo?.get("package_name") == appIdForOAuth
  }
val oauthClients = matchingFirebaseClient?.get("oauth_client") as? List<*>
val canonicalWebClientId = oauthClients
  ?.mapNotNull { it as? Map<*, *> }
  ?.firstOrNull { it["client_type"].toString() == "3" }
  ?.get("client_id")?.toString()?.trim()
  ?.takeIf { it.isNotBlank() }
  ?: throw GradleException("No Web OAuth client (client_type=3) is registered for $appIdForOAuth in app/google-services.json.")

fun isPlaceholderWebClientId(value: String?): Boolean {
  val candidate = value?.trim().orEmpty()
  return candidate.isBlank() || candidate.equals("web_client_mock_id", ignoreCase = true) ||
    candidate.contains("mock", ignoreCase = true) || candidate.contains("dummy", ignoreCase = true) ||
    candidate.contains("example", ignoreCase = true)
}

val suppliedWebClientId = System.getenv("WEB_CLIENT_ID")?.trim()
// The AI Studio shell may export its generic mock value. In that case use the exact client
// registered in google-services.json. A non-placeholder but different ID remains an error.
val envWebClientId = if (isPlaceholderWebClientId(suppliedWebClientId)) canonicalWebClientId else suppliedWebClientId!!
val envFile = rootProject.file(".env")
val envExampleFile = rootProject.file(".env.example")
val requiredBuildConfigKeys = listOf("CLOUDINARY_CLOUD_NAME", "CLOUDINARY_UPLOAD_PRESET", "WEB_CLIENT_ID")
val exampleLines = if (envExampleFile.isFile) envExampleFile.readLines() else emptyList()
val exampleValues = exampleLines.mapNotNull { line ->
  val split = line.indexOf('=')
  if (split <= 0 || line.trimStart().startsWith("#")) null
  else line.substring(0, split).trim() to line.substring(split + 1).trim()
}.toMap()
val sourceEnvLines = if (envFile.isFile) envFile.readLines() else exampleLines
val seenEnvKeys = mutableSetOf<String>()
val normalizedEnvLines = sourceEnvLines.map { line ->
  val split = line.indexOf('=')
  if (split <= 0 || line.trimStart().startsWith("#")) line
  else {
    val key = line.substring(0, split).trim()
    val currentValue = line.substring(split + 1).trim()
    seenEnvKeys += key
    when (key) {
      "WEB_CLIENT_ID" -> "$key=$envWebClientId"
      in requiredBuildConfigKeys -> if (currentValue.isBlank()) {
        // A non-empty, recognizable placeholder keeps generated BuildConfig source valid;
        // app code must treat these fallback values as unconfigured at runtime.
        "$key=${exampleValues[key]?.takeIf { it.isNotBlank() } ?: "UNCONFIGURED"}"
      } else line
      else -> line
    }
  }
}.toMutableList()
for (key in requiredBuildConfigKeys) {
  if (key !in seenEnvKeys) {
    val fallback = if (key == "WEB_CLIENT_ID") envWebClientId else exampleValues[key]?.takeIf { it.isNotBlank() } ?: "UNCONFIGURED"
    normalizedEnvLines += "$key=$fallback"
  }
}
val normalizedEnvText = normalizedEnvLines.joinToString("\n") + "\n"
if (!envFile.isFile || envFile.readText() != normalizedEnvText) envFile.writeText(normalizedEnvText)

// Configure the Secrets Gradle Plugin to use .env and .env.example files.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

abstract class ValidateWebClientIdTask : DefaultTask() {
  @get:Internal
  abstract val envFile: RegularFileProperty

  @get:Input
  abstract val environmentWebClientId: Property<String>

  @get:Input
  abstract val expectedWebClientId: Property<String>

  @TaskAction
  fun validate() {
    val fileClientId = if (envFile.isPresent && envFile.get().asFile.exists()) {
      envFile.get().asFile.readLines()
        .firstOrNull { it.trim().startsWith("WEB_CLIENT_ID=") }
        ?.substringAfter("=")?.trim()
    } else null
    val environmentId = environmentWebClientId.orNull?.trim().orEmpty()
    val expectedId = expectedWebClientId.get().trim()
    val resolvedId = fileClientId?.takeIf { it.isNotBlank() } ?: environmentId
    val isInvalid = resolvedId != expectedId || environmentId != expectedId ||
      resolvedId.isBlank() || resolvedId.contains("mock", ignoreCase = true) ||
      resolvedId.contains("dummy", ignoreCase = true) || resolvedId.contains("example", ignoreCase = true)
    if (isInvalid) {
      println("[WebClientIdValidation] FAIL: WEB_CLIENT_ID does not match the registered Firebase Web OAuth client.")
      throw GradleException("Build failed: WEB_CLIENT_ID is missing, a placeholder, or differs from the client in app/google-services.json.")
    } else {
      println("[WebClientIdValidation] PASS: WEB_CLIENT_ID matches the registered Firebase Web OAuth client.")
    }
  }
}

tasks.register<ValidateWebClientIdTask>("validateWebClientId") {
  envFile.set(rootProject.layout.projectDirectory.file(".env"))
  environmentWebClientId.set(envWebClientId)
  expectedWebClientId.set(canonicalWebClientId)
}

tasks.named("preBuild").configure {
  dependsOn("validateWebClientId")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.ERROR }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation("com.startapp:inapp-sdk:5.1.0")
  implementation(libs.cloudinary.android)
    implementation(libs.play.services.nearby)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.1")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation("com.google.zxing:core:3.5.3")
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.exoplayer.hls)
  implementation(libs.androidx.media3.ui)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.messaging)
  // implementation("com.google.firebase:firebase-storage")

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.jsoup)
  implementation(libs.androidx.work.runtime.ktx)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
