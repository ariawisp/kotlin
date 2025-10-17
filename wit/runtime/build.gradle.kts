description = "Runtime support library for the Kotlin WIT compiler plugin"

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    // Additional targets (e.g. Native) can be added once WIT codegen requires them.

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
}
