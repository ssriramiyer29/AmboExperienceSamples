plugins {
    id("com.android.application")
}

val aepVersion = providers.gradleProperty("aep.version").get()

android {
    namespace = "com.ambokit.aep.rockdodge.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ambokit.aep.rockdodge.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.5.1"
    }
}

dependencies {
    implementation(project(":core"))

    // The three published AEP components, vendored in libs/. Nothing here refers to AEP
    // source: this sample can only build from artifacts, which is the point of it.
    implementation(files("$rootDir/libs/AEP.Core-$aepVersion.jar"))
    implementation(files("$rootDir/libs/AEP.HostSDK-$aepVersion.aar"))
    implementation(files("$rootDir/libs/AEP.AndroidTV.Adapter-$aepVersion.aar"))

    // AEP.HostSDK's own runtime dependency, declared here because a file dependency carries
    // no POM and so brings nothing transitively with it. Leaving it out produces a build that
    // succeeds and then dies with NoClassDefFoundError the moment the host opens a socket.
    //
    // Keep this in step with the Host SDK when bumping aep.version - see libs/README.md.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // This sample's own dependency, not AEP's: it renders the join QR code on screen.
    implementation("com.google.zxing:core:3.5.3")
}
