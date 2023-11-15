# Terracotta Test Tools

This module holds testing tools useful across Terracotta OSS modules in _including_
those tools borrowed from elsewhere.

## Borrowed Tools

### `TemporaryFolder` from JUnit 4

* The version of `org.junit.rules.TemporaryFolder` (at commit `038f7518fc1018b26df608e3e5dce6db4611be29`, tag `r4.13` 
corresponding to the version released in JUnit 4.13) was lifted from
the JUnit 4 code base (<https://github.com/junit-team/junit4>) to pick up changes related
to issue [_TemporaryFolder doesn't work for parallel test execution in several JVMs_ #1223](https://github.com/junit-team/junit4/issues/1223).
Other than relocating the package from `org.junit.rules` to `org.terracotta.org.junit.rules`
(and adding necessary imports), the captured files are unchanged.  Its use is governed
by `LICENSE-junit.txt` found at <https://raw.githubusercontent.com/junit-team/junit4/master/LICENSE-junit.txt>.

* The versions of `org.junit.tules.TemporaryFolder` and `org.junit.rules.TempFolderRuleTest` (at commit
`1b683f4ec07bcfa40149f086d32240f805487e66`, tag `r4.13.1`) were lifted to pick up changes related to
the resolution of CVE-2020-15250.

* A local update is made to `org.terracotta.org.junit.rules.TemporaryFolderUsageTest` to avoid a test failure 
when tests are run in a Docker container (during CI) running as root.

**NOTE:** To limit changes to the files obtained from the JUnit code base to the absolute minimum, 
the Terracotta copyright header is intentionally omitted and SpotBugs/FindBugs is intentionally suppressed.


## Dependency Declarations for `terracotta-utilities-test-tools`

The dependency declarations needed for the `terracotta-utilities-test-tools` module depends on:

1. the build system, e.g. Maven vs Gradle
2. the tools needed

The `terracotta-utilities-test-tools` module contains the following general tool categories:

1. tools used for writing JUnit4/Hamcrest tests
2. tools used to verify Logback event output
3. tools not requiring either JUnit4/Hamcrest or Logback

The dependency declarations are described below.

### Maven Declarations

For Maven builds, the `terracotta-utilities-test-tools` module has several _optional_ dependencies.
The dependency declarations needed vary with the tools you wish to use.

#### Minimal Declaration

The following Maven dependency declaration is required for all uses of `terracotta-utilties-test-tools`:

    <dependency>
      <groupId>org.terracotta</groupId>
      <artifactId>terracotta-utilities-test-tools</artifactId>
      <version>${toolVersion}</version>
      <scope>test</scope>
    </dependency>

The above declaration supports the use of the tooling not requiring JUnit 4, Hamcrest, or Logback,
for example, `Diagnostics`.  To use the JUnit4/Hamcrest and/or Logback tools, additional dependencies
must be added.

#### JUnit4/Hamcrest Tooling

To use the JUnit4/Hamcrest tools, the following declarations must be added:

    <dependency>
      <groupId>junit</groupId>
      <artifactId>junit</artifactId>
      <version>4.12</version>
      <scope>test</scope>
      <exclusions>
        <exclusion>
          <groupId>org.hamcrest</groupId>
          <artifactId>hamcrest-core</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>org.hamcrest</groupId>
      <artifactId>hamcrest</artifactId>
      <version>2.2</version>
      <scope>test</scope>
    </dependency>

#### Logback Tooling

To use the Logback tools (`ConsoleAppenderCapture`), the following declarations must 
be added:

    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>[1.2.11,1.2.9999)</version>
    </dependency>

You may _pin_ the version to any version within the specified range.


### Gradle Declarations

For Gradle builds, this module defines three (3) feature variants each with a uniquely-defined capability:

  "main" / capability "org.terracotta:terracotta-utilities-test-tools:<version>"
  : For use when JUnit 4 and Hamcrest tools are needed; this is considered the typical use and
    is the variant chosen when `org.terracotta.terracotta-test-tools` is declared as the 
    dependency.

  "logback" / capability "org.terracotta:terracotta-utilities-test-tools-logback:<version>"
  : For use when the logging test helpers are needed (`ConsoleAppenderCapture`).

  "default" / capability "org.terracotta:terracotta-utilities-test-tools-base:<version>"
  : For use when neither JUnit4/Hamcrest nor logging tools are needed (`Diagnostics`).

The variants differ in the transitive dependencies each declares; multiple variants may be
combined, as described below, in order to use multiple capabilities.

The dependency declarations shown in the sections that follow are for Kotlin build scripts.

#### JUnit4/Hamcrest Tooling

To use JUnit4/Hamcrest tools, the following dependency declaration should be used:

    testImplementation("org.terracotta:terracotta-utilities-test-tools:${toolVersion}")

#### Logback Tooling

To use the Logback tools, the following dependency declaration should be used:

    testImplementation("org.terracotta:terracotta-utilities-test-tools:${toolVersion}") {
      capabilities {
        requireCapability("org.terracotta:terracotta-utilities-test-tools-logback")
      }
    }

#### Basic Tooling

If neither the JUnit4/Hamcrest nor Logback capabilities are needed (for example, to
use only the `Diagnostics` tool), the following dependency declaration can be used:

    testImplementation("org.terracotta:terracotta-utilities-test-tools:${toolVersion}") {
        capabilities {
            requireCapability("org.terracotta:terracotta-utilities-test-tools-base")
        }
    }

#### Combined Tooling

The `Diagnostics` tool is available with each of the dependency declarations above.  To use both
the JUnit4/Hamcrest tools and Logback tools, use _both_ the JUnit4/Hamcrest and Logback declarations 
as follows:

    testImplementation("org.terracotta:terracotta-utilities-test-tools:${toolVersion}")
    testImplementation("org.terracotta:terracotta-utilities-test-tools:${toolVersion}") {
      capabilities {
        requireCapability("org.terracotta:terracotta-utilities-test-tools-logback")
      }
    }
