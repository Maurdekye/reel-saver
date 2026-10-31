import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from environment variables (CI) or a properties file kept outside the repo:
// its path is the Gradle property reelsaver.signingProperties (e.g. in ~/.gradle/gradle.properties),
// falling back to an untracked keystore.properties next to this project.
val keystoreProps = Properties().apply {
    val path = providers.gradleProperty("reelsaver.signingProperties").orNull
    val f = if (path != null) file(path) else rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStoreFile: String? = signingValue("REELSAVER_KEYSTORE_FILE", "storeFile")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

// The one place the version lives. Release tags must be "v" + this (checked by the release workflow).
val appVersion = "1.0.0"
val appVersionCode = appVersion.split('.').map { it.toInt() }.let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

android {
    namespace = "io.github.maurdekye.reelsaver"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.maurdekye.reelsaver"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("REELSAVER_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("REELSAVER_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("REELSAVER_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
