plugins {
    id("rates.hotpath-conventions")
    `java-test-fixtures`
}

dependencies {
    // Startup only: InstrumentsYaml reads config/instruments.yaml before any hot path thread starts.
    implementation(libs.snakeyaml.engine)
}
