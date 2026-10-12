// Applied by hot path modules: everything in rates.java-conventions plus the ban on reading
// system time directly (CLAUDE.md rule 4, FR.STR.03).

plugins {
    id("rates.java-conventions")
}

val mainSourceSet = the<SourceSetContainer>()["main"]
val checkBannedTimeCalls = tasks.register<BannedTimeCallsCheck>("checkBannedTimeCalls") {
    description = "Fails when main source calls System.nanoTime, currentTimeMillis or Instant.now"
    group = "verification"
    sources.from(mainSourceSet.java.srcDirs)
    // SystemClock is the single place allowed to read system time.
    allowedFileNames.add("SystemClock.java")
    reportFile = layout.buildDirectory.file("reports/banned-time-calls.txt")
}

tasks.named("check") {
    dependsOn(checkBannedTimeCalls)
}
