# Terracotta Utilities
This project contains utility classes that may be used among all
Terracotta-OSS projects.  

## Project Constraints
This project operates under the following constraints:

1. **Changes must not be _breaking_**; programs built to compile and run
    using version 0.0.1 artifacts from this project must be able to 
    compile and run with version 0.0.2 artifacts from this project.
2. **This project should not be _branched_**.  Maintaining multiple versions
    of this project is not desirable.    
3. The `tools` module may not rely on any third-party artifacts other than:
    * `org.slf4j:slf4j-api`
    * `com.github.spotbugs:spotbugs-annotations`
4. The `test-tools` module may rely on test-support artifacts in 
    common use though these artifacts should generally be optional
    for consumers of the `test-tools` module.  For example, the
    following artifacts might be used:
    * `org.hamcrest:hamcrest:2.2`   (compile)
    * `junit:junit:4.12`            (provided)
    * `org.testng:testng:6.8`       (provided)
    
    If an artifact on which `test-tools` relies makes a breaking change,
    introduce new artifact containing the breaking components -- not a 
    new version of the `test-tools` artifact. 
