import java.time.Duration

plugins {
    base
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
    id("org.nosphere.apache.rat")
    id("net.researchgate.release") version "3.0.2"
}

val defaultVersion: String by project
val sonatypeUser: String by project
val sonatypePwd: String by project

group = "org.terracotta"

repositories {
    mavenCentral()      // See https://github.com/eskatos/creadur-rat-gradle/issues/26
}

tasks {
    rat {
        approvedLicense("Apache License Version 2.0")
        include("buildSrc/src/**")
        exclude("**/org.terracotta.java-conventions.gradle.kts")
    }
}

nexusPublishing {
    repositories {
        sonatype {
            username = sonatypeUser
            password = sonatypePwd
        }
    }
    // Sonatype is often very slow in these operations:
    transitionCheckOptions {
        delayBetween = Duration.ofSeconds((findProperty("delayBetweenRetriesInSeconds") ?: "10").toString().toLong())
        maxRetries = (findProperty("numberOfRetries") ?: "100").toString().toInt()
    }
}

release {
    git {
        requireBranch.set("master")
    }
    versionProperties.set(listOf("defaultVersion"))
    tagTemplate.set("v\${version}")

    // work around this issue by tacking on "build" again:
    // https://github.com/researchgate/gradle-release/blob/af4bf46b/src/main/groovy/net/researchgate/release/ReleasePlugin.groovy#L153
    buildTasks.set(listOf("build", "publish", "build"))
    if (project.hasProperty("postReleaseBuildTasks")) {
        // specify what to do after tagging/building and before updating to a SNAPSHOT version, eg:
        // ./gradlew release -PpostReleaseBuildTasks="closeSonatypeStagingRepository"
        buildTasks.addAll((project.property("postReleaseBuildTasks") as String).split(" "))
    }
}
