plugins {
    id("com.android.application")
}

val aepVersion = providers.gradleProperty("aep.version").get()

android {
    namespace = "com.ambokit.aep.airdraw.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ambokit.aep.airdraw.tv"
        // 26 rather than RockDodge's 24: AirDraw is new, and the earlier AirDraw RC already
        // required it. Nothing here needs to run on Android 7.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":core"))

    // The three published AEP components, vendored in libs/. Nothing here refers to AEP source:
    // this sample can only build from artifacts, which is the point of it.
    implementation(files("$rootDir/libs/AEP.Core-$aepVersion.jar"))
    implementation(files("$rootDir/libs/AEP.HostSDK-$aepVersion.aar"))
    implementation(files("$rootDir/libs/AEP.AndroidTV.Adapter-$aepVersion.aar"))

    // AEP.HostSDK's own runtime dependency, declared here because a file dependency carries no
    // POM and brings nothing transitively. Leaving it out builds fine and then dies with
    // NoClassDefFoundError the moment the host opens a socket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // This sample's own dependency, not AEP's: it renders the join QR code on screen.
    implementation("com.google.zxing:core:3.5.3")
}
