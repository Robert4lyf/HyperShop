plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Robolectric's Android runtimes, as ordinary dependencies.
//
// Left to itself Robolectric downloads these jars at test time, outside Gradle: nothing caches them in CI, and a
// test run fails whenever Maven Central does not answer at that moment ("AssertionError at MavenArtifactFetcher",
// every test class of a module at once). Here Gradle resolves them (retried, cached with everything else) and
// Robolectric is told to use that folder and fetch nothing.
//
// The names belong to the Robolectric version in libs.versions.toml (4.16.1: SDK 34 and SDK 36). After a Robolectric
// bump a missing runtime fails the tests by name; put the new name here.
val robolectricRuntimes = listOf("14-robolectric-10818077-i7", "16-robolectric-13921718-i7")

subprojects {
    val wire = {
        // One configuration each: they are two versions of one module, and a shared one would keep only the newer.
        val runtimes =
            robolectricRuntimes.map {
                configurations.detachedConfiguration(dependencies.create("org.robolectric:android-all-instrumented:$it")).apply {
                    isTransitive = false
                }
            }
        val jars =
            tasks.register<Sync>("robolectricRuntimeJars") {
                from(runtimes)
                into(layout.buildDirectory.dir("robolectric-runtime"))
            }
        tasks.withType<Test>().configureEach {
            dependsOn(jars)
            systemProperty("robolectric.offline", "true")
            // A path of this machine: given as an argument, not an input, so the build cache still matches elsewhere.
            val dir = layout.buildDirectory.dir("robolectric-runtime")
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Drobolectric.dependency.dir=${dir.get().asFile.absolutePath}") })
        }
    }
    plugins.withId("com.android.application") { wire() }
    plugins.withId("com.android.library") { wire() }
}
