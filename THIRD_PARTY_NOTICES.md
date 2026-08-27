# Third-party notices

The Orafit JDBC distribution includes the following third-party component at
runtime. The corresponding license text is also included in the fat JAR under
`META-INF/`.

## JSqlParser 5.3.218

- Maven coordinate: `com.manticore-projects.jsqlformatter:jsqlparser:5.3.218`
- License: dual licensed under LGPL 2.1 or Apache License 2.0
- Orafit license choice: Apache License 2.0
- Project: <https://github.com/JSQLParser/JSqlParser>
- License text in this repository: `third-party/jsqlparser/LICENSE-APACHE-2.0.txt`
- License text in the distribution: `META-INF/JSqlParser-LICENSE-APACHE-2.0.txt`

Orafit elects to use and distribute JSqlParser under the Apache License 2.0
option. The LGPL 2.1 option remains the upstream project's alternative license;
this distribution does not rely on that option. The upstream dual-license
statement is documented in the JSqlParser project and its Maven metadata.

Orafit's own source code is distributed under the Orafit License in `LICENSE`.

## Zonky Embedded PostgreSQL (test runtime only)

- Maven coordinates: `io.zonky.test:embedded-postgres:2.2.2`
- PostgreSQL binary coordinate: `io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:17.10.0`
- Runtime target: glibc-based Linux amd64; Windows, macOS, and Alpine binaries are not bundled
- License: Apache License 2.0
- Project: <https://github.com/zonkyio/embedded-postgres>

The embedded PostgreSQL library and native binaries are used only by the Maven
test suite and are not included in the Orafit product JAR.
