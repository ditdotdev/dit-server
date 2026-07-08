# Dit Docker Server

![](https://github.com/ditdotdev/dit-server/workflows/Nightly%20Tests/badge.svg)
![GitHub release (latest by date)](https://img.shields.io/github/v/release/ditdotdev/dit-server)

This repository contains the docker container that is used by the
[Dit](https://github.com/ditdotdev/dit) data management tool. This docker container
provides all the repository operations necessary to support dit-powered containers, along with 
remote providers to push & and pull data between repositories. For information on the Dit
project and community, see [dit.dev](https://dit.dev).

This is an internal dependency of Dit, and should never need to be run by itself.

## CI/CD Pipeline

This repository includes a comprehensive Pull Request 2 workflow with:
- Cross-platform testing (Ubuntu, Windows, macOS)
- Multi-version Go support (1.21, 1.22, 1.23)
- Security scanning and code quality checks
- Coverage reporting and performance benchmarks

## Contributing

The ZFS builder project follows the Dit community best practices:

  * [Contributing](https://github.com/ditdotdev/.github/blob/master/CONTRIBUTING.md)
  * [Code of Conduct](https://github.com/ditdotdev/.github/blob/master/CODE_OF_CONDUCT.md)
  * [Community Support](https://github.com/ditdotdev/.github/blob/master/SUPPORT.md)

It is maintained by the [Dit community maintainers](https://github.com/ditdotdev/.github/blob/master/MAINTAINERS.md)

For more information on how it works, and how to build and release new versions,
see the [Development Guidelines](DEVELOPING.md).

<!-- Build trigger: Testing modernized CI/CD workflow -->

## License

This project is licensed under the Business Source License 1.1 (BUSL-1.1).
On the Change Date (four years from the publication of each version), the
license for that version converts to the Mozilla Public License 2.0
(MPL-2.0). See [LICENSE](LICENSE) for the full terms.
