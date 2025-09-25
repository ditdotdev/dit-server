# Titan Docker Server

![](https://github.com/titan-data/titan-server/workflows/Nightly%20Tests/badge.svg)
![GitHub release (latest by date)](https://img.shields.io/github/v/release/titan-data/titan-server)

This repository contains the docker container that is used by the
[Titan](https://github.com/titan-data/titan) data management tool. This docker container
provides all the repository operations necessary to support titan-powered containers, along with 
remote providers to push & and pull data between repositories. For information on the Titan
project and community, see [titan-data.io](https://titan-data.io).

This is an internal dependency of Titan, and should never need to be run by itself.

## CI/CD Pipeline

This repository includes a comprehensive Pull Request 2 workflow with:
- Cross-platform testing (Ubuntu, Windows, macOS)
- Multi-version Go support (1.21, 1.22, 1.23)
- Security scanning and code quality checks
- Coverage reporting and performance benchmarks

## Contributing

The ZFS builder project follows the Titan community best practices:

  * [Contributing](https://github.com/titan-data/.github/blob/master/CONTRIBUTING.md)
  * [Code of Conduct](https://github.com/titan-data/.github/blob/master/CODE_OF_CONDUCT.md)
  * [Community Support](https://github.com/titan-data/.github/blob/master/SUPPORT.md)

It is maintained by the [Titan community maintainers](https://github.com/titan-data/.github/blob/master/MAINTAINERS.md)

For more information on how it works, and how to build and release new versions,
see the [Development Guidelines](DEVELOPING.md).

## License

This is code is licensed under the Apache License 2.0. Full license is
available [here](./LICENSE).
