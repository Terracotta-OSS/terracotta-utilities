/**
 * Gradle init script to correct an unset 'user.name' Java system property resulting from the defect
 * described in https://bugs.openjdk.java.net/browse/JDK-8193433.
 */
val userHome: String? = System.getProperty("user.home")
if (userHome.isNullOrEmpty() || userHome.equals("?")) {
    val home = System.getenv("HOME")
    if (home.isNullOrEmpty()) {
        throw IllegalStateException("environment variable \"HOME\" must be set when user.home='${userHome}'")
    }

    /*
     * Replace Java's broken user.home value in both this JVM and any spawned JVM (ex. for tests).
     */
    logger.quiet("Setting user.home=\"${home}\"")
    System.setProperty("user.home", home)

    gradle.allprojects {
        tasks.withType<Test> {
            logger.quiet("Setting ${this.path} user.home=\"${home}\"")
            systemProperty("user.home", home)
        }
        tasks.withType<JavaExec> {
            logger.quiet("Setting ${this.path} user.home=\"${home}\"")
            systemProperty("user.home", home)
        }
    }
} else {
    logger.quiet("user.home=\"${userHome}\"")
}
