plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
