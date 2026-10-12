// Applied by every Java module: Java 21 bytecode and APIs, strict compiler warnings, JUnit 5 and AssertJ.

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

// The toolchain runs compile and tests. CI overrides it with -PjavaToolchain=25 to run the
// same build on Java 25, while options.release keeps bytecode and the JDK API at 21.
val toolchainVersion = providers.gradleProperty("javaToolchain").getOrElse("21").toInt()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
