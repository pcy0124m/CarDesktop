// 与 AdBlocker 保持同一套已验证的工具链版本，避免重复踩版本兼容坑
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
