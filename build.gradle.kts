// Top-level build file
// AGP 9 は Kotlin コンパイルを内蔵している (built-in Kotlin) ため、
// org.jetbrains.kotlin.android は適用しない (適用するとエラーになる)。
// 使われる KGP の版は buildscript classpath 上の最大版で決まり、
// Compose コンパイラプラグインが同版の kotlin-gradle-plugin を引き込むので、
// 実質ここの Compose プラグインの版が Kotlin の版になる。
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
