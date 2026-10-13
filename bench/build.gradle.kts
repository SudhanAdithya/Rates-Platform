// JMH microbenchmarks (local only, not in CI) and the end to end latency harness.

plugins {
    id("rates.java-conventions")
    application
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":schemas"))
    implementation(project(":common"))
    implementation(project(":venue-sim"))
    implementation(libs.hdrhistogram)
}

application {
    mainClass = "io.github.sudhanadithya.rates.bench.LatencyHarness"
}

// Fixed, pretouched heap so page faults and heap resizing don't land in the measurement.
tasks.named<JavaExec>("run") {
    jvmArgs("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch")
}

jmh {
    jmhVersion = libs.versions.jmh.asProvider()
    // Short runs keep a local pass under a few minutes; raise these for numbers you publish.
    warmupIterations = 3
    warmup = "1s"
    iterations = 5
    timeOnIteration = "1s"
    fork = 1
    timeUnit = "ns"
    benchmarkMode = listOf("avgt")
    // Adds gc.alloc.rate.norm (bytes allocated per operation): hot path benchmarks must show 0.
    profilers = listOf("gc")
    jvmArgsAppend = listOf(
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch",
    )
}
