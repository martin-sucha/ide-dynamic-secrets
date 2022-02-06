<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Dynamic Secrets Changelog

## [Unreleased]

### Added

* Support for platform version `2021.3`

### Changed

### Deprecated

### Removed

* Support for platform version `2021.1`

### Fixed

## [0.1.0]
### Added

* Support for platform version `2021.1`.

### Removed

* Support for platform version `<2021.1`.

### Fixed

* Fixed race condition when assigning leases to database connections. 

## [0.0.1]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Support for setting environment variables in Go and Python run configurations (and revoking the leases)
- Support for dynamic secrets when connecting in database tools (and revoking lease)
