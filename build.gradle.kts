// Top-level build file. Plugin versions are declared once here (apply false)
// so the :app module can apply them without repeating the version number.
//
// NOTE: RecorderX targets Android Gradle Plugin 9.x, which has *built-in Kotlin
// support* (enabled by default since AGP 9.0). That means we deliberately do NOT
// apply org.jetbrains.kotlin.android here -- AGP compiles the .kt sources itself.
// See: https://developer.android.com/build/migrate-to-built-in-kotlin
plugins {
    id("com.android.application") version "9.3.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
