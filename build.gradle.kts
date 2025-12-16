import java.time.Duration

plugins {
    base
    id("org.nosphere.apache.rat")
    id("net.researchgate.release") version "3.0.2"
    id("com.jfrog.artifactory") version "6.0.4"
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


allprojects {
    if (project.hasProperty("overrideVersion")) {
        project.version = project.property("overrideVersion") as String
    } else {
        project.version = project.property("defaultVersion") as String
    }
    
    // Apply and configure artifactory for subprojects
    if (project != rootProject) {
        apply(plugin = "com.jfrog.artifactory")
        
        // Wait for maven-publish plugin to be applied by java-conventions
        pluginManager.withPlugin("maven-publish") {
            project.tasks.findByPath("publish")?.dependsOn(project.tasks.getByPath("artifactoryPublish"))
            
            // Configure artifactory task after evaluation when publications are created
            afterEvaluate {
                tasks.withType<org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask>().configureEach {
                    publications("maven")
                }
            }
        }
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

if (project.hasProperty("overrideVersion")) {
    tasks.named("artifactoryPublish") {
        enabled = false
    }

    artifactory {
        setContextUrl("https://na.artifactory.swg-devops.com/artifactory")
        publish(Action<org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig> {
            repository(Action<org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig.Repository> {
                setRepoKey("hyc-webmriab-team-tc01-os-releases-maven-local")
                setUsername(project.findProperty("artifactory_user") as? String ?: System.getenv("ARTIFACTORY_DEPLOY_USERNAME"))
                setPassword(project.findProperty("artifactory_password") as? String ?: System.getenv("ARTIFACTORY_DEPLOY_PASSWORD"))
            })
        })
        clientConfig.info.setBuildName("hyc-webmriab-team-tc01/terracotta/${rootProject.name}")
        clientConfig.info.setBuildNumber(rootProject.findProperty("buildNumber") as? String ?: "" + java.util.Random(System.currentTimeMillis()).nextInt(20000))
    }
}
