plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation(project(":core"))
    implementation("org.usb4java:usb4java:1.3.0")
}

application {
    mainClass.set("org.mxltv.desktop.MainKt")
}
