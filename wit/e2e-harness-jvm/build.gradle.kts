plugins {
    kotlin("jvm")
}

description = "Preview-2 E2E harness (JVM) for WIT compiler plugin"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("com.ariawisp.wit:kotlin-wit-parser:0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(17)
}


tasks.test {
    useJUnitPlatform()
}
