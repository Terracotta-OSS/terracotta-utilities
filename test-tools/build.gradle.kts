plugins {
    id("org.terracotta.java-conventions")
}

val logbackRangeVersion: String by project
val hamcrestVersion: String by project
val junitVersion: String by project
val slf4jRangeVersion: String by project

extra["pomName"] = "Terracotta Utilities Test Tools"
description = "Utility classes/methods for use in testing"

/*
 * See README.md for a description of the use of the feature/capability and
 * the Maven and Gradle declarations needed for each feature/capability.
 */

java {
    /*
     * The ("main") configuration carries the JUnit/Hamcrest dependencies and
     * has the "normal" or "default" capability for this module.  This is done
     * because this is the typical usage of this module.  (The api and
     * implementation configurations for the "normal"/"default"  feature
     * are changed to a "base" capability below.)
     */
    registerFeature("main") {
        usingSourceSet(sourceSets.main.get())
        capability(group as String, name, version as String)
    }

    /*
     * 'logback' configuration is needed when 'ConsoleAppenderCapture'
     * is used.
     */
    registerFeature("logback") {
        usingSourceSet(sourceSets.main.get())
    }
}

configurations {
    apiElements {
        outgoing {
            // "Default" api configuration moves to "base" capability
            capability("${project.group}:${project.name}-base:${project.version}")
        }
    }
    runtimeElements {
        outgoing {
            // "Default" runtime configuration moves to "base" capability
            capability("${project.group}:${project.name}-base:${project.version}")
        }
    }

    /*
     * Anchor each feature's apiElements and runtimeElements configurations in the
     * primary/default compileOnlyApi/api and implementation/runtimeOnly configurations.
     * See https://docs.gradle.org/current/userguide/java_library_plugin.html#sec:java_library_configurations_graph.
     */
    "mainApiElements" {
        extendsFrom(api.get(), compileOnlyApi.get())
    }
    "mainRuntimeElements" {
        extendsFrom(implementation.get(), runtimeOnly.get())
    }
    "logbackApiElements" {
        extendsFrom(api.get(), compileOnlyApi.get())
    }
    "logbackRuntimeElements" {
        extendsFrom(implementation.get(), runtimeOnly.get())
    }
}

dependencies {
    "mainApi"("org.hamcrest:hamcrest:${hamcrestVersion}")
    "mainApi"("junit:junit:${junitVersion}") {
        exclude(mapOf("group" to "org.hamcrest"))
    }

    "logbackImplementation"("ch.qos.logback:logback-core:${logbackRangeVersion}")
    "logbackApi"("ch.qos.logback:logback-classic:${logbackRangeVersion}")
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
            // Suppress junk from imported TemporaryFolder package
            addBooleanOption("Xdoclint/package:-org.terracotta.org.junit.*", true)      // Java 9+
        }
    }
}

tasks.test {
    filter {
        // Don't look for nested classes
        excludeTestsMatching("*$*")
    }
}

testing {
    suites {
        named<JvmTestSuite>(JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME) {
            targets {

                val withoutConsole by creating {
                    testTask {
                        filter {
                            includeTestsMatching("org.terracotta.utilities.test.logging.ConsoleAppenderCaptureNoConsoleTest")
                        }
                        systemProperty("logback.configurationFile", "noConsoleAppender.xml")
                    }
                }

                named(this@named.name) {
                    testTask {
                        dependsOn(withoutConsole)
                    }
                }
            }
        }
    }
}

spotbugs {
    excludeFilter.value(layout.projectDirectory.file("config/spotbugs/excludeFilter.xml"))
}

val junitLicense = project.copySpec {
    into("META-INF/licenses/junit/junit") {
        from(layout.projectDirectory) {
            include("*-junit*")
        }
    }
}

tasks.jar {
    with(junitLicense)
}

tasks.sourcesJar {
    with(junitLicense)
    into("") {
        from(layout.projectDirectory.file("README.md"))
    }
}

publishing {
    publications.withType<MavenPublication> {
        suppressPomMetadataWarningsFor("apiElements")
        suppressPomMetadataWarningsFor("runtimeElements")
        suppressPomMetadataWarningsFor("logbackApiElements")
        suppressPomMetadataWarningsFor("logbackRuntimeElements")
        pom {
            licenses {
                license {
                    //  License for JUnit TemporaryFolder
                    name = "Eclipse Public License - v 1.0"
                    url = "https://www.eclipse.org/legal/epl-v10.html"
                }
            }
        }
    }
}

tasks.rat {
    exclude("README.md")
    exclude("LICENSE-*")
    exclude("NOTICE-*")
    exclude("src/main/java/org/terracotta/org/junit/**")
    exclude("src/test/java/org/terracotta/org/junit/**")
}
