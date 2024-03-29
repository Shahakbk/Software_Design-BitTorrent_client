plugins {
    application
    id("org.jetbrains.dokka") version "0.10.1"
}

application {
    mainClassName = "il.ac.technion.cs.softwaredesign.MainKt"
}

val externalLibraryVersion: String? by extra
val junitVersion: String? by extra
val hamkrestVersion: String? by extra
val guiceVersion: String? by extra
val kotlinGuiceVersion: String? by extra
val mockkVersion: String? by extra

dependencies {
    implementation(project(":library"))

    implementation("il.ac.technion.cs.softwaredesign", "primitive-storage-layer", externalLibraryVersion)
    implementation("com.google.inject", "guice", guiceVersion)
    implementation("dev.misfitlabs.kotlinguice4", "kotlin-guice", kotlinGuiceVersion)

    testImplementation("org.junit.jupiter", "junit-jupiter-api", junitVersion)
    testImplementation("org.junit.jupiter", "junit-jupiter-params", junitVersion)
    testImplementation("com.natpryce", "hamkrest", hamkrestVersion)
    testImplementation("io.mockk", "mockk", mockkVersion)

    // For main
    implementation("com.xenomachina", "kotlin-argparser", "2.0.7")
}

tasks {
    val dokka by getting(org.jetbrains.dokka.gradle.DokkaTask::class) {
        outputFormat = "html"
        outputDirectory = "$buildDir/dokka"

        configuration {
            // Used to exclude non public members.
            includeNonPublic = false
        }
    }
}