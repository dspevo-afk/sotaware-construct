plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

private fun org.gradle.api.provider.ProviderFactory.releaseSigningValue(
    propertyName: String,
    environmentName: String
) = gradleProperty(propertyName).orElse(environmentVariable(environmentName))

private fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseStoreFile = providers.releaseSigningValue(
    propertyName = "sotawareReleaseStoreFile",
    environmentName = "SOTAWARE_RELEASE_STORE_FILE"
)
val releaseStorePassword = providers.releaseSigningValue(
    propertyName = "sotawareReleaseStorePassword",
    environmentName = "SOTAWARE_RELEASE_STORE_PASSWORD"
)
val releaseKeyAlias = providers.releaseSigningValue(
    propertyName = "sotawareReleaseKeyAlias",
    environmentName = "SOTAWARE_RELEASE_KEY_ALIAS"
)
val releaseKeyPassword = providers.releaseSigningValue(
    propertyName = "sotawareReleaseKeyPassword",
    environmentName = "SOTAWARE_RELEASE_KEY_PASSWORD"
)
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.orNull.isNullOrBlank() }
val googleWebClientId = providers.releaseSigningValue(
    propertyName = "sotawareGoogleWebClientId",
    environmentName = "SOTAWARE_GOOGLE_WEB_CLIENT_ID"
).orNull?.trim().orEmpty()

android {
    namespace = "com.example.myapplication"
    compileSdk = 36

    defaultConfig {
        // Keep the implementation namespace stable to avoid a cosmetic source
        // package migration. This is the permanent installable app identity.
        applicationId = "com.sotaware.construct"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // A web OAuth client ID is public client configuration, not a secret.
        // Leave it empty in ordinary local builds until Cloud configuration is
        // complete; the Credential Manager adapter fails closed at runtime.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", googleWebClientId.asBuildConfigString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                val configuredStore = File(requireNotNull(releaseStoreFile.orNull))
                val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
                require(configuredStore.isAbsolute) {
                    "SOTAware release keystore path must be absolute and outside the repository"
                }
                require(!configuredStore.canonicalFile.toPath().startsWith(repositoryRoot)) {
                    "SOTAware release keystore must live outside the repository"
                }
                storeFile = configuredStore
                storePassword = requireNotNull(releaseStorePassword.orNull)
                keyAlias = requireNotNull(releaseKeyAlias.orNull)
                keyPassword = requireNotNull(releaseKeyPassword.orNull)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            // Ensure pdfbox-android resource files (glyph lists, encodings) are packaged
            pickFirsts.add("com/tom_roush/pdfbox/resources/**")
            pickFirsts.add("com/tom_roush/fontbox/resources/**")
            pickFirsts.add("com/tom_roush/pdfbox/**")
            pickFirsts.add("com/tom_roush/fontbox/**")
            // Handle Google API duplicate files
            excludes.add("META-INF/DEPENDENCIES")
            excludes.add("META-INF/LICENSE")
            excludes.add("META-INF/LICENSE.txt")
            excludes.add("META-INF/license.txt")
            excludes.add("META-INF/NOTICE")
            excludes.add("META-INF/NOTICE.txt")
            excludes.add("META-INF/notice.txt")
            excludes.add("META-INF/ASL2.0")
        }
    }
}

tasks.register("verifySotawareReleaseSigning") {
    group = "verification"
    description = "Fails closed unless all external SOTAware release signing inputs are available."
    doLast {
        check(releaseSigningReady) {
            "Release signing is not configured. Supply sotawareReleaseStoreFile, " +
                "sotawareReleaseStorePassword, sotawareReleaseKeyAlias, and " +
                "sotawareReleaseKeyPassword as user-level Gradle properties or " +
                "SOTAWARE_RELEASE_* environment variables."
        }
    }
}

tasks.configureEach {
    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease", "installRelease")) {
        dependsOn("verifySotawareReleaseSigning")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Removed legacy Play Services Vision; using ML Kit text-recognition instead
    // ML Kit on-device text recognition (used as OCR fallback)
    // Upgrade to the latest ML Kit text recognition for better performance and bug fixes
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.pdf.viewer)
    implementation(libs.androidx.appcompat)
    implementation("androidx.compose.ui:ui-viewbinding")
    implementation("androidx.compose.material:material-icons-extended")
    // Google Drive API for backup sync
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240123-2.0.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.44.1")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
