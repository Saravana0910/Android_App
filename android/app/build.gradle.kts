plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun maevaProperty(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

val maevaBaseUrl = maevaProperty("maevaBaseUrl", "https://renaultarca--hquat.sandbox.my.site.com").trimEnd('/')
val maevaSitePath = maevaProperty("maevaSitePath", "/NestSFA/s/")
val maevaAllowedDomains = maevaProperty(
    "maevaAllowedDomains",
    "force.com,salesforce.com,my.site.com,salesforce-experience.com,visualforce.com,documentforce.com"
)

android {
    namespace = "com.renault.maeva"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.renault.maeva"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("maevaVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = maevaProperty("maevaVersionName", "1.0.0")

        buildConfigField("String", "MAEVA_BASE_URL", "\"$maevaBaseUrl\"")
        buildConfigField("String", "MAEVA_SITE_PATH", "\"$maevaSitePath\"")
        buildConfigField("String", "MAEVA_ALLOWED_DOMAINS", "\"$maevaAllowedDomains\"")

        resourceConfigurations += listOf("en", "fr")
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("MAEVA_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("MAEVA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MAEVA_KEY_ALIAS")
                keyPassword = System.getenv("MAEVA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix: google-services.json is keyed by package
            // name, and a suffixed debug id would need a second Firebase client.
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

val validateReleaseSigning by tasks.registering {
    doLast {
        val required = listOf(
            "MAEVA_KEYSTORE_PATH",
            "MAEVA_KEYSTORE_PASSWORD",
            "MAEVA_KEY_ALIAS",
            "MAEVA_KEY_PASSWORD"
        )
        val missing = required.filter { System.getenv(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing is required. Missing: ${missing.joinToString()}. " +
                "Use assembleDebug for an installable test APK without release credentials."
        }
        check(file(System.getenv("MAEVA_KEYSTORE_PATH")).isFile) {
            "MAEVA_KEYSTORE_PATH must point to an existing signing keystore."
        }
    }
}

tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease", "validateSigningRelease")) {
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}

// The Google Services plugin hard-fails when google-services.json is absent.
// Applying it conditionally lets the project build (without push) before the
// Firebase project exists. See README.md -> "Firebase setup".
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    logger.lifecycle("Maeva: google-services.json found, Firebase push is enabled.")
} else {
    logger.warn("Maeva: google-services.json is missing, building WITHOUT push notifications.")
}
