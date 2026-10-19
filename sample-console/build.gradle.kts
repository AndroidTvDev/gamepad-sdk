plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }
application { mainClass.set("dev.gamepad.sdk.sample.MainKt") }
dependencies {
    implementation(project(":sdk-core"))
    implementation(project(":protocol-demo"))
}
