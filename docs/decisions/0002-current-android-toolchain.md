# 0002 — Use current compatible Android dependencies

Status: approved by supervisor for foundation, 2026-10-19. No additional user-data,
provider, release-signing or publication scope is introduced.

The first debug APK compiled and seven JVM tests passed, but strict lint found
newer AndroidX/Compose releases alongside source issues. Rather than suppress the
advisories, update the foundation stack and preserve all lint gates and assertions.

| Component | Approved version |
| --- | --- |
| Android Gradle plugin | 9.4.1 |
| Gradle wrapper | 9.6.0, official SHA-256 pinned |
| Kotlin / Compose compiler plugin | 2.4.21, using AGP built-in Kotlin |
| Compose BOM | 2026.09.00 |
| AndroidX core-ktx | 1.19.1 |
| AndroidX activity-compose | 1.13.0 |
| AndroidX lifecycle runtime/compose | 2.11.0 |
| Compile / target SDK | 37 |
| SDK build tools | 36.0.0 |
| Minimum SDK | 26, unchanged |
| Build JDK | 21 |
| Local test library | JUnit 4.13.2, unchanged |

Official evidence read during selection:

- [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
  support API 37, specify Gradle 9.6.0 as minimum/default, build-tools 36.0.0 and JDK >= 17.
- [Google Maven AGP metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
  lists 9.4.1 as current stable. Its POM embeds KGP 2.2.10, so explicit Kotlin/compiler
  alignment is required during the built-in Kotlin migration.
- Downloaded official core 1.19.1 AAR metadata requires compile SDK 37/AGP >= 9.1.0;
  activity 1.13.0 AAR metadata requires compile SDK 36/AGP >= 8.9.1.
- [Kotlin Maven metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml)
  lists 2.4.21 as current stable.

Use the supported built-in Kotlin DSL. Do not retain deprecated opt-outs or
turn off dependency, API, backup or source lint checks to get a green result.
Actual compatibility is established by final assemble, JVM tests and strict lint,
then independent reviewer and red-team checks on the exact candidate.

This update extends the manual device matrix to API 36/37. Device/large-library
acceptance remains blocked without hardware or accelerated emulation. Rollback
means returning to an earlier development branch, never changing user media.
