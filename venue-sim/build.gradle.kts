// The simulated CME style exchange: matching engine now; synthetic flow, the A/B feed and
// chaos switches arrive in later M2 tasks.

plugins {
    id("rates.hotpath-conventions")
}

dependencies {
    implementation(project(":common"))
    // Side and the other wire enums are shared with the feed the venue publishes.
    implementation(project(":schemas"))
    implementation(libs.agrona)

    testImplementation(testFixtures(project(":common")))
}
