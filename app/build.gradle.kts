plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

val appVersion = "0.2.0"

/**
 * versionName から versionCode を作る (0.2.0 → 200、1.0.0 → 10000)。JPNKN Vox と同じ規則。
 *
 * versionCode は OS が「どちらが新しいか」を比べるためだけの整数で、小さい値の APK は上書きできない。
 * 桁を固定せずに繋げると 0.1.10 (110) より 0.2.0 (20) が小さくなって逆転するため、
 * minor と patch に 2 桁ずつ割り当てる (それぞれ 0〜99 まで)。
 * v0.1.0 = 1、v0.1.1 = 2 は旧規則 (+1) で公開済みだが、200 以降はすべてそれより大きいので問題ない。
 */
val appVersionCode = appVersion.split(".")
    .map { it.toInt() }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// リリース署名の情報は keystore.properties (git 管理外) から読む。
// 無ければ release は未署名のままビルドされる (署名鍵を持たない環境でもビルドを通すため)。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.titagaki.genkaibroadcaster"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.titagaki.genkaibroadcaster"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion

        vectorDrawables {
            useSupportLibrary = true
        }
        // 起動アイコンのラベル。debug ではビルドタイプ側で上書きして見分ける
        resValue("string", "app_name", "Genkai Broadcaster")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // release 版と別アプリとして共存させる (同じ applicationId だと署名鍵が違うため上書きできない)。
        // 設定 (SharedPreferences) も分かれる。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Genkai Broadcaster (debug)")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // AGP 内蔵 Kotlin では jvmTarget は targetCompatibility に追従するので別途指定しない
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG をデバッグ専用機能の判定に使う
        resValues = true // app_name の resValue (debug のラベル切替)。AGP 9 では既定で無効
        aidl = true // コメント提供アプリとの I/F (src/main/aidl)。AGP 8 以降は既定で無効
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // StateFlow / SharedFlow を直接使うので推移依存に頼らず明示する
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // RTMP streaming (camera + mic encoder) - RootEncoder (pedroSG94)
    // https://github.com/pedroSG94/RootEncoder
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")

    // JVM 単体テスト。org.json は android.jar のスタブが例外を投げるため実装を差し込む
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// リリース APK を配布用の名前 (genkai-broadcaster-v<versionName>.apk) でコピーする。
// assembleRelease (Android Studio の Build → Generate APKs の release も同じ) の後に自動で走る。
// AGP 9 では旧 applicationVariants API が無く、新 API の VariantOutput にも公開の
// outputFileName が無いため、内部クラスに触らず Copy タスクで済ませる。
val distReleaseApk = tasks.register<Copy>("distReleaseApk") {
    val distName = "genkai-broadcaster-v$appVersion.apk"
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("app-release.apk") }
    into(layout.buildDirectory.dir("outputs/dist"))
    rename { distName }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(distReleaseApk) }
