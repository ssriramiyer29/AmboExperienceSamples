// Rock Dodge game rules and state: no renderer, no platform. Kotlin/JVM, so the same rules
// can be exercised without an emulator.
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // compileOnly: these rules are written against AEP Core's types but do not ship it. The
    // app module puts AEP on the runtime classpath, and declaring it in both places would put
    // the same jar there twice.
    compileOnly(files("$rootDir/libs/AEP.Core-${providers.gradleProperty("aep.version").get()}.jar"))
}
