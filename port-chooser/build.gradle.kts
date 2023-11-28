plugins {
    id("org.terracotta.java-conventions")
    id("org.terracotta.utilities.plugins.plantuml")
}

val logbackRangeVersion: String by project

extra["pomName"] = "Terracotta Utilities Port Chooser"
description = "Utility classes for TCP port management"

dependencies {
    api(project(":terracotta-utilities-tools")) {
        exclude(mapOf("group" to "org.slf4j", "module" to "slf4j-api"))
    }
    testImplementation(project(":terracotta-utilities-test-tools"))
    testImplementation("ch.qos.logback:logback-classic:${logbackRangeVersion}")
}

testing {
    suites {
        named<JvmTestSuite>(JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME) {
            targets {

                val testIPv4_base by creating {
                    // Run test using IPv4 preference
                    testTask {
                        filter {
                            setIncludePatterns("org.terracotta.utilities.test.net.*Test")
                            // Exclude single test run below
                            setExcludePatterns("org.terracotta.utilities.test.net.PortManagerTest")
                        }
                        systemProperty("java.net.preferIPv4Stack", "true")
                        systemProperty("org.terracotta.disablePortReleaseCheck", "false")
                        environment("DISABLE_PORT_RELEASE_CHECK" to "")
                    }
                }

                val testIPv4_A by creating {
                    // Repeat tests using IPv4 preference - single test of long-running suite
                    testTask {
                        filter {
                            includeTest("org.terracotta.utilities.test.net.PortManagerTest", "testReleaseCheckEnabled")
                        }
                        systemProperty("java.net.preferIPv4Stack", "true")
                        systemProperty("org.terracotta.disablePortReleaseCheck", "false")
                        environment("DISABLE_PORT_RELEASE_CHECK" to "")
                    }
                }

                val testPortCheckDisabled by creating {
                    // Single test for DISABLE_PORT_RELEASE_CHECK=true
                    testTask {
                        filter {
                            includeTest("org.terracotta.utilities.test.net.PortManagerTest", "testReleaseCheckDisabledEnvironment")
                        }
                        environment("DISABLE_PORT_RELEASE_CHECK" to "true")
                    }
                }

                named(this@named.name) {
                    testTask {
                        dependsOn(
                            testIPv4_base,
                            testIPv4_A,
                            testPortCheckDisabled
                        )
                        // Standard tests run with DISABLE_PORT_RELEASE_CHECK false or unset
                        environment("DISABLE_PORT_RELEASE_CHECK" to "")
                    }
                }
            }
        }
    }
}
