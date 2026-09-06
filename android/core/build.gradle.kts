plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit"))
}
val reference = tasks.register<Exec>("generateReference") {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/reference.mjs", layout.buildDirectory.file("reference.json").get().asFile)
    inputs.files(rootProject.file("../search-card.js"), rootProject.file("compatibility.json"),
        rootProject.file("tools/reference.mjs"), rootProject.file("fixtures/search.json"))
    outputs.file(layout.buildDirectory.file("reference.json"))
}
tasks.test {
    dependsOn(reference)
    inputs.files(rootProject.file("fixtures/search.json"), layout.buildDirectory.file("reference.json"))
    systemProperty("fixtures", rootProject.file("fixtures/search.json").absolutePath)
    systemProperty("reference", layout.buildDirectory.file("reference.json").get().asFile.absolutePath)
}
