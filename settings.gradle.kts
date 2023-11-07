rootProject.name = "terracotta-utilities-parent"

include(":terracotta-utilities-tools")
include(":terracotta-utilities-port-chooser")
include(":terracotta-utilities-test-tools")

project(":terracotta-utilities-tools").projectDir = file("tools")
project(":terracotta-utilities-port-chooser").projectDir = file("port-chooser")
project(":terracotta-utilities-test-tools").projectDir = file("test-tools")
