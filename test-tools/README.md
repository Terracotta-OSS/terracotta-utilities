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

**NOTE:** To limit changes to the files obtained from the JUnit code base to the absolute minimum, 
the Terracotta copyright header is intentionally omitted and SpotBugs/FindBugs is intentionally suppressed.
