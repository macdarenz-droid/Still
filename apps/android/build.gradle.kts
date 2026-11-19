plugins {
    id("com.android.application") version "9.4.1" apply false
    // Pin the KGP used by AGP's built-in Kotlin without applying the legacy Android plugin.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
