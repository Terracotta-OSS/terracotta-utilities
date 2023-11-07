plugins {
    id("org.terracotta.java-conventions")
}

val logbackRangeVersion: String by project

description = "Terracotta Utilities Tools"

dependencies {
    testImplementation(project(":terracotta-utilities-test-tools"))
    testImplementation("ch.qos.logback:logback-classic:${logbackRangeVersion}")
}

tasks.rat {
    exclude("src/test/resources/De_finibus_bonorum_et_malorum_Liber_Primus.txt")
}

tasks.test {
    systemProperties.put("dumputility.diagnostics.enable", System.getProperty("dumputility.diagnostics.enable"))
    if (JavaVersion.current() >= JavaVersion.VERSION_17) {
        //  DumpUtilityTest needs add-opens option for complete testing under Java 17+.
        jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
    }
}
