// SBE schemas and the Java codecs generated from them at build time (FRS TC.03).
// Generated code goes to build/generated/sources/sbe and is never committed.

plugins {
    id("rates.java-conventions")
}

val sbeCodegen = configurations.create("sbeCodegen")

dependencies {
    sbeCodegen(libs.sbe.tool)
    // Generated codecs expose Agrona's DirectBuffer and MutableDirectBuffer in their API.
    api(libs.agrona)
}

val sbeSchemaDir = layout.projectDirectory.dir("src/main/resources/sbe")
val sbeOutputDir = layout.buildDirectory.dir("generated/sources/sbe")

val generateSbeCodecs = tasks.register<JavaExec>("generateSbeCodecs") {
    description = "Generates Java codecs from the SBE schemas"
    group = "build"

    inputs.dir(sbeSchemaDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(sbeOutputDir)

    classpath = sbeCodegen
    mainClass = "uk.co.real_logic.sbe.SbeTool"
    systemProperty("sbe.output.dir", sbeOutputDir.get().asFile.absolutePath)
    systemProperty("sbe.target.language", "Java")
    systemProperty("sbe.validation.stop.on.error", "true")
    systemProperty("sbe.validation.warnings.fatal", "true")
    args(sbeSchemaDir.file("rates-md.xml").asFile.absolutePath)

    // Clear stale codecs so a removed message doesn't linger in the output. A plain File is
    // captured because the configuration cache can't serialize references to the build script.
    val outputDirFile = sbeOutputDir.get().asFile
    doFirst {
        outputDirFile.deleteRecursively()
    }
}

sourceSets {
    main {
        java.srcDir(generateSbeCodecs)
    }
}
