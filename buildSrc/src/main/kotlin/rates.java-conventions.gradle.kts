// Applied by every Java module: Java 21 bytecode and APIs, strict compiler warnings, JUnit 5 and AssertJ.

plugins {
    `java-library`
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

// Agrona 2.x reads jdk.internal.misc.Unsafe directly, so every JVM that loads Agrona
// (tests, JavaExec tasks, and later the core processes and JMH forks) needs this export.
val agronaJvmArgs = listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs(agronaJvmArgs)
}

// Tests tagged "allocation" prove a code path allocates nothing. They run only here, in their
// own JVM under Epsilon GC (a collector that never frees memory), so any steady allocation
// eventually exhausts the heap. Each test also asserts zero bytes with the thread allocation
// counter, which fails fast with a readable message. check depends on it, so build runs it.
val testSourceSet = the<SourceSetContainer>()["test"]
val allocationTest = tasks.register<Test>("allocationTest") {
    description = "Runs tests tagged 'allocation' under Epsilon GC"
    group = "verification"
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("allocation")
    }
    jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC", "-Xms512m", "-Xmx512m")
    // Tells AllocationAssertions to verify Epsilon really is the active collector.
    systemProperty("rates.allocationTest", "true")
    // Each test class gets a fresh JVM so heap used by one class can't starve the next.
    forkEvery = 1
    // A module with no allocation tests yet is fine.
    filter.isFailOnNoMatchingTests = false
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("allocation")
    }
}

tasks.named("check") {
    dependsOn(allocationTest)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(agronaJvmArgs)
}
