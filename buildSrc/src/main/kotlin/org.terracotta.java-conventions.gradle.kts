import com.github.spotbugs.snom.SpotBugsPlugin
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.Action
import org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE
import org.gradle.api.plugins.JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME
import java.util.regex.Pattern

plugins {
    `java-library`
    `maven-publish`
    signing
    id("checkstyle")
    id("com.github.spotbugs")
    id("org.nosphere.apache.rat")
}

val slf4jBaseVersion: String by project
val slf4jUpperVersion: String by project
val slf4jRangeVersion by extra("[${slf4jBaseVersion},${slf4jUpperVersion})")
val logbackBaseVersion: String by project
val logbackUpperVersion: String by project
val logbackRangeVersion by extra("[${logbackBaseVersion},${logbackUpperVersion})")
val junitVersion: String by project
val hamcrestVersion: String by project
val mockitoVersion: String by project
val spotbugsAnnotationsVersion: String by project

group = "org.terracotta"
version = project.rootProject.version

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

dependencies {
    api("org.slf4j:slf4j-api:${slf4jRangeVersion}")
    compileOnly("com.github.spotbugs:spotbugs-annotations:${spotbugsAnnotationsVersion}")

    testCompileOnly("com.github.spotbugs:spotbugs-annotations:${spotbugsAnnotationsVersion}")
    testImplementation("org.hamcrest:hamcrest:${hamcrestVersion}")
    testImplementation("junit:junit:${junitVersion}") {
        exclude(mapOf("group" to "org.hamcrest"))
    }
    testImplementation("org.mockito:mockito-core:${mockitoVersion}")
}

configurations.all {
    // Spotbugs has an internal SLF4J conflict ...
    if (SpotBugsPlugin.CONFIG_NAME != this.name) {
        resolutionStrategy {
            failOnVersionConflict()
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

publishing {
    repositories {
        // For publication testing ...
        maven(uri(layout.buildDirectory.dir("publishing-repository"))) {
            name = "buildLocal"
        }
    }

    publications.register<MavenPublication>("maven") {
        from(components["java"])

        pom {
            description.convention(project.provider { project.description })
            name.convention(project.provider { project.extra["pomName"] as String })
            url.convention("https://github.com/terracotta-oss/terracotta-utilities/")

            licenses {
                license {
                    name.convention("The Apache License, Version 2.0")
                    url.convention("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    name.convention("Terracotta Engineers")
                    email.convention("tc-oss-dg@ibm.com")
                    organization.convention("IBM Corp.")
                    organizationUrl.convention("https://terracotta.org")
                }
            }
            scm {
                connection.convention("scm:git:https://github.com/terracotta-oss/terracotta-utilities.git")
                developerConnection.convention("scm:git:git@github.com:terracotta-oss/terracotta-utilities.git")
                url.convention("https://github.com/terracotta-oss/terracotta-utilities")
            }
        }
    }
}

checkstyle {
    toolVersion = "9.3"     // Latest version supporting Java 8
    configFile = rootDir.resolve("config/checkstyle.xml")
    configDirectory.convention(layout.projectDirectory.dir("config/checkstyle"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.isDeprecation = true
        options.isWarnings = true
        // '-Xlint:options' needed to ignore warning about source/target Java 8 obsolescence
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options", "-Werror"))
    }

    withType<Javadoc> {
        isFailOnError = false

        (options as StandardJavadocDocletOptions).apply {
            quiet()
            encoding = "UTF-8"
            author(false)
            showFromProtected()
            use(true)

            // Avoid complaints about tablew@summary
            addBooleanOption("Xdoclint:all,-accessibility", true)  // https://discuss.gradle.org/t/how-to-send-x-option-to-javadoc/23384/5
        }
    }

    test {
        useJUnit()
    }

    withType<SpotBugsTask> {
        reports {
            register("xml") {
                required.set(false)
            }
            register("html") {
                required.set(true)
            }
        }
    }

    spotbugsMain {
        val spotbugsLastCheckLevel = JavaVersion.VERSION_21     // Most current Java version assessed for Spotbugs support
        val spotbugsSuppressionLevel = JavaVersion.VERSION_21   // Java version at which Spotbugs is suppressed
        if (JavaVersion.current() > spotbugsLastCheckLevel) {
            throw GradleException("Current version of Java exceeds Spotbugs suppression level; re-examine Spotbugs suppression", null)
        } else if (JavaVersion.current() >= spotbugsSuppressionLevel) {
            logger.warn("Suppressing Spotbugs; current Java Version ${JavaVersion.current()} >= Spotbugs support version ${spotbugsSuppressionLevel}")
            enabled = false
        } else {
            enabled = true
        }
    }

    spotbugsTest {
        enabled = false
    }

    rat {
        approvedLicense("Apache License Version 2.0")
        exclude("build/**")     // Avoid implicit dependency error on task outputs
        exclude("target/**")
        exclude("build.gradle.kts")
    }

    // CopySpec common to executable and sources Jars
    val pomAndLicense = project.copySpec {
        into("META-INF/maven/${project.group}/${project.name}") {
            from({ tasks.named("generatePomFileForMavenPublication") })
            rename(Pattern.quote("pom-default.xml"), "pom.xml")
        }
        into("") {
            from(project.rootProject.rootDir.resolve("LICENSE"))
        }
    }

    val versionPattern = Pattern.compile("(?<major>\\d+)\\.(?<minor>\\d+)\\..*")
    @Throws(InvalidUserDataException::class)
    fun specVersion(version: String) : String {
        val matcher = versionPattern.matcher(version)
        return if (matcher.matches()) {
            matcher.group("major") + "." + matcher.group("minor")
        } else {
            throw InvalidUserDataException("Version string \"${version}\" is not in major.minor.<rest> format")
        }
    }

    // Manifest construct common to executable and sources Jars
    val commonManifest = Action<Manifest> {
        val jdkSpec = configurations.named(RUNTIME_ELEMENTS_CONFIGURATION_NAME)
            .map { c -> JavaVersion.toVersion(c.attributes.getAttribute(TARGET_JVM_VERSION_ATTRIBUTE) ?: 8) }

        attributes(
            linkedMapOf(
                "Build-Jdk-Spec" to jdkSpec,
                "Built-By" to System.getProperty("user.name"),
                "Build-Jdk" to System.getProperty("java.version"),
                "Specification-Title" to project.extra["pomName"],
                "Specification-Version" to specVersion(project.version.toString()),
                "Implementation-Title" to project.name,
                "Implementation-Vendor-Id" to project.group,
                "Implementation-Version" to project.version,
            )
        )
    }

    jar {
        with(pomAndLicense)
        manifest(commonManifest)
    }

    named<Jar>("sourcesJar") {
        with(pomAndLicense)
        manifest(commonManifest)
    }
}

signing {
    /*
      * By default, signing uses the 'signing.keyId', 'signing.secretKeyRingFile', and 'signing.password'
      * values provided through the gradle.properties file (or some other Gradle property-setting mechanism).
      * This scheme does not require special signatory setup methods.
      *
      * For CI-based publishing, use of the 'ORG_GRADLE_PROJECT_{signingKey,signingPassword,signingKeyId}'
      * environment variables supply an ASCII-armored key and require the use of the 'useInMemoryPgpKeys'
      * setup method.
      *
      * If 'ORG_GRADLE_PROJECT_signingKey' environment variable is set, use the 'useInMemoryPgpKeys'
      * method; otherwise, the default, property-based method is used.
      */
    if (hasProperty("signingKey")) {
        val signingKey: String by project
        val signingPassword: String by project
        if (hasProperty("signingKeyId")) {
            val signingKeyId: String by project
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        } else {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
    }

    // Require signing when requested -- either explicitly or via "publish"
    setRequired({ gradle.taskGraph.hasTask(tasks.named("signMavenPublication").get()) })
    sign(publishing.publications)
}
