pluginManagement {
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "PrivateIme"

// Pure-Kotlin, Android-free decision engine. Everything that decides whether a
// field is sensitive, what may be learned, and what must be deleted lives here
// so it can be exercised by fast JVM tests.
include(":core")

// The Android IME shell: EditorInfo / accessibility signal collection, the
// keyboard itself, local storage, and the settings/cleanup UI.
include(":app")
