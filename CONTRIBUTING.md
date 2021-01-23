## Run plugin in different IDE than Idea

The build system is configured to use IU (IntelliJ Idea Ultimate)
as platform. If you want to run the plugin in a different IDE (PyCharm, GoLand, etc.)
set `runIdeDirectory` gradle property to path to the IDE.

For example copy `Run Plugin` run configuration and add  `-P`
[cli option](https://docs.gradle.org/current/userguide/command_line_interface.html) to arguments:

```
-PrunIdeDirectory=/home/martin/.local/share/JetBrains/Toolbox/apps/Goland/ch-0/203.5981.98
```