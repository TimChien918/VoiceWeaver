plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.kotlin.serialization)
  id("com.google.gms.google-services")
}

android {
  namespace = "com.speakmylife.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.speakmylife.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 162
    versionName = "1.6.2"

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
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  androidResources {
    // MediaPipe .task 模型若放進 assets/models，必須保持不壓縮；
    // 否則 Android 只能用 stream 讀，無法提供穩定檔案長度與快速複製。
    noCompress += listOf("task", "bin", "gguf", "tflite", "wasm")
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      // NativeRuleCritic 這類純邏輯類別會呼叫 android.util.Log，JVM 測試環境沒有實作
      // → 預設丟 "not mocked" 例外，測試根本走不到斷言。讓未實作的 android.* 回預設值。
      isReturnDefaultValues = true
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
// Room 的 schema JSON。exportSchema = true 沒有這一行會在編譯期警告，
// 而且沒有基準檔就無法驗證 migration——AphasiaDatabase 刻意不用 destructive fallback，
// 所以這些檔案要進版控。
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
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
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  implementation("com.google.firebase:firebase-auth")
  implementation("com.google.firebase:firebase-firestore")
  implementation("com.google.android.gms:play-services-auth:21.2.0")
  implementation(libs.mediapipe.tasks.genai)
  implementation(libs.mediapipe.tasks.vision)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.play.services.mlkit.`object`.detection)
  // 影像標籤（400+ 具體標籤）：物件偵測的預設分類器只有 5 個粗類（Fashion/Food/Home/Place/Plant），
  // 認不出「水杯」「藥袋」這種具體物品；改用 ImageLabeling 才有辦法給 AAC 有用的詞。
  implementation(libs.mlkit.image.labeling)
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  // Vosk 離線語音辨識（純裝置端 AI ASR，免帳號、免 Google 語言包）
  implementation("com.alphacephei:vosk-android:0.3.75")

  // ── 失語症溝通引擎（Edge AI）─────────────────────────────────────────
  // TFLite：語音嵌入模型。MFCC 本身是純 DSP，用 TFLite 跑並不會比手寫 Kotlin 快，
  // 所以特徵抽取自行實作；TFLite 保留給「學習型嵌入」那條路線（對構音障礙的
  // 變異容忍度優於原始 MFCC），先以 MFCC 打通管線再 A/B。
  // 只收 tensorflow-lite 本體。tensorflow-lite-support 0.4.4 鎖著舊的
  // tensorflow-lite-api:2.13.0，與 2.17.0 內建的 LiteRT 整包類別衝突；
  // 而它的 helper 是給影像／文字用的，音訊嵌入直接餵 FloatArray 進 Interpreter 即可。
  implementation(libs.tensorflow.lite)
  // 註 1：FastDTW 沒有維護良好的 Maven 套件，且現成實作普遍缺少
  //   Sakoe-Chiba band 與路徑長度正規化——這兩者在本專案是正確性問題，
  //   不是最佳化。因此 DTW 自行實作，見 aphasia/data/acoustic。
  // 註 2：向量檢索沒有用 ObjectBox。它的 Gradle plugin 不支援 configuration cache
  //   （PrepareTask 序列化 DefaultProject 失敗），而本專案全域開著。
  //   規模是「數百～數千句取 Top-5」，Room + 暴力 cosine 在此量級是次毫秒等級，
  //   HNSW 沒有實質收益。介面 PhrasebookRepository.searchSimilar 有隔離，日後要換不影響呼叫端。
  implementation(libs.retrofit)
  implementation(libs.retrofit.converter.serialization)
  implementation("androidx.webkit:webkit:1.11.0")
  // 背景排程：每日復健提醒通知 + 成績單定期推送家屬
  implementation("androidx.work:work-runtime-ktx:2.9.1")
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
