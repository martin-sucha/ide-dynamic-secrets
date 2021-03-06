# ide-dynamic-secrets

[![Build](https://github.com/martin-sucha/ide-dynamic-secrets/workflows/Build/badge.svg)](https://github.com/martin-sucha/ide-dynamic-secrets/actions)
<!-- hide badges as plugin is not on marketplace yet
[![Version](https://img.shields.io/jetbrains/plugin/v/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)
-->

<!-- Plugin description -->
IDE Dynamic Secrets plugin allows using secrets stored in Hashicorp Vault from within IntelliJ Platform based IDEs:

* Use secrets when connecting to databases in database tools and revoke lease when connection is disconnected.
  The plugin adds "Dynamic Secrets" authentication type to data source properties.
* Expose secrets as environment variables when running your programs and revoke those secrets when the program finishes.
  The plugin adds "Dynamic Secrets" tab to Run/Debug configurations.

  Supported run configurations:

  * Go
  * Python

<!-- Plugin description end -->

<details>
<summary>Screenshots</summary>

![Data source screenshot](screenshots/data-source.png)
![Run configuration screenshot](screenshots/run-config.png)

</details>

## Installation

<!-- not available on marketplace yet
- Using IDE built-in plugin system:
  
  <kbd>Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "IDE Dynamic Secrets"</kbd> >
  <kbd>Install Plugin</kbd>
  -->
- Manually:

  Download the [latest release](https://github.com/martin-sucha/ide-dynamic-secrets/releases/latest) and install it manually using
  <kbd>Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


## License

Unless expressly stated otherwise, all files in this repository are licensed under the Apache License, version 2.0
(see LICENSE.txt).

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
