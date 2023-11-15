plugins {
    `kotlin-dsl`
}

repositories {
    // Use the plugin portal to apply community plugins in convention plugins.
    gradlePluginPortal()
}

dependencies {
    implementation("net.sourceforge.plantuml:plantuml:1.2023.11")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:5.0.13")
    implementation("org.nosphere.apache.rat:org.nosphere.apache.rat.gradle.plugin:0.8.0")
}

gradlePlugin {
    plugins {
        create("plantUml") {
            id = "org.terracotta.utilities.plugins.plantuml"
            implementationClass = "org.terracotta.utilities.plugins.PlantUmlPlugin"
        }
    }
}
