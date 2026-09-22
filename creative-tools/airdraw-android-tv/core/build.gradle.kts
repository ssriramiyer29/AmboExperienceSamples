// AirDraw's drawing rules: stroke smoothing, the document, the viewport, gesture interpretation.
// No renderer, no Android (ADR-0001 rule 2). Kotlin/JVM, so the arithmetic that makes the product
// good can be tested without a TV in the room - and it is, because that arithmetic IS the product.
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // compileOnly: these rules read AEP's typed capability models but do not ship AEP. The app
    // module puts it on the runtime classpath, and declaring it twice would put the same jar
    // there twice.
    compileOnly(files("$rootDir/libs/AEP.Core-${providers.gradleProperty("aep.version").get()}.jar"))

    testImplementation(kotlin("test"))
    testImplementation(files("$rootDir/libs/AEP.Core-${providers.gradleProperty("aep.version").get()}.jar"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
