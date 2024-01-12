plugins {
    id("org.terracotta.java-conventions")
}

val logbackRangeVersion: String by project

extra["pomName"] = "Terracotta Utilities Tools"
description = "Utility classes/methods for common Java tasks"

dependencies {
    testImplementation(project(":terracotta-utilities-test-tools"))
    testImplementation("ch.qos.logback:logback-classic:${logbackRangeVersion}")
}

tasks.rat {
    exclude("src/test/resources/De_finibus_bonorum_et_malorum_Liber_Primus.txt")
}

testing {
    suites {
        withType<JvmTestSuite> {
            targets.configureEach {
                testTask {
                    systemProperties.put("dumputility.diagnostics.enable", System.getProperty("dumputility.diagnostics.enable"))
                }
            }
        }

        named<JvmTestSuite>(JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME) {
            targets {
                val testTasks = mutableListOf<JvmTestSuiteTarget>()
                if (JavaVersion.current() >= JavaVersion.VERSION_17) {

                    val testJava17Opens by creating {
                        testTask {
                            description = "Runs tests requiring --add-opens JVM options"
                            //  Java 17+ needs some --add-opens for full testing ...
                            jvmArgs(
                                "--add-opens=java.base/java.nio=ALL-UNNAMED",               // DumpUtility
                                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"       // MemoryInfo
                            )
                            filter {
                                includeTestsMatching("DumpUtilityTest")
                                includeTestsMatching("MemoryInfoTest")
                            }
                        }
                    }
                    testTasks.add(testJava17Opens)

                    // Java 17+ for MemoryInfo : --add-opens and MaxDirectMemorySize
                    val testJava17OpensMax by creating {
                        testTask {
                            description = "Runs tests requiring --add-opens and MaxDirectMemorySize JVM options"
                            jvmArgs(
                                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                                "-XX:MaxDirectMemorySize=512M"
                            )
                            filter {
                                includeTestsMatching("MemoryInfoTest")
                            }
                        }
                    }
                    testTasks.add(testJava17OpensMax)
                }

                // MemoryInfo : MaxDirectMemorySize
                val testMaxDirect by creating {
                    testTask {
                        description = "Runs tests requiring MaxDirectMemorySize JVM options"
                        jvmArgs(
                            "-XX:MaxDirectMemorySize=512M"
                        )
                        filter {
                            includeTestsMatching("MemoryInfoTest")
                        }
                    }
                }
                testTasks.add(testMaxDirect)

                named(this@named.name) {
                    testTask {
                        dependsOn(
                            testTasks.toTypedArray()
                        )
                    }
                }
            }
        }
    }
}