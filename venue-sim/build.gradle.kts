// The simulated CME style exchange: matching engine, seeded synthetic flow, the MDP style A/B
// incremental feed, snapshot and definitions loops, and chaos switches. Process main in task 2.6.

plugins {
    id("rates.hotpath-conventions")
}

dependencies {
    implementation(project(":common"))
    // Side and the other wire enums are shared with the feed the venue publishes.
    implementation(project(":schemas"))
    implementation(libs.agrona)
    implementation(libs.aeron.client)

    // Tests run an embedded media driver; the venue process itself gets one in task 2.6.
    testImplementation(libs.aeron.driver)
    testImplementation(testFixtures(project(":common")))
}
