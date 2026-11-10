// Install-time asset pack with every bundled model, for the Google Play bundle only
// (`./gradlew bundleRelease -Pplay`, see settings.gradle.kts). Play installs it together with
// the app, and its assets appear at the same paths (assets/models/...) as in the universal APK.
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// The pack reads src/main/assets; the models themselves stay in bundled-models/ (fetched and
// checked by :app:fetchModels) and are copied in here at build time (git-ignored).
val syncModels by tasks.registering(Sync::class) {
    dependsOn(":app:fetchModels")
    from(rootProject.layout.projectDirectory.dir("bundled-models/assets/models"))
    into(layout.projectDirectory.dir("src/main/assets/models"))
}
tasks.configureEach { if (name != "syncModels") dependsOn(syncModels) }
