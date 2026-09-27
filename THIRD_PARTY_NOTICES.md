# Third-party notices

This document identifies selected third-party components used by Alertify.
Each component remains subject to its own license. These notices do not grant
a license to Alertify's original code or replace the components' license texts,
copyright notices, or other required notices. This is not an exhaustive
inventory of all application dependencies or container-image components.

## Frontend dependencies

The following versions and license identifiers are recorded in
`frontend/package-lock.json`:

| Component | Version | License | Upstream project |
| --- | --- | --- | --- |
| Angular (`@angular/common`, `@angular/compiler`, `@angular/core`, `@angular/forms`, `@angular/platform-browser`, `@angular/router`) | 22.1.2 | MIT | https://github.com/angular/angular |
| ngx-echarts | 22.0.0 | MIT | https://github.com/xieziyu/ngx-echarts |
| Apache ECharts | 6.1.0 | Apache-2.0 | https://github.com/apache/echarts |
| Keycloak JS | 26.2.4 | Apache-2.0 | https://github.com/keycloak/keycloak-js |
| RxJS | 7.8.2 | Apache-2.0 | https://github.com/ReactiveX/rxjs |
| zrender | 6.1.0 | BSD-3-Clause | https://github.com/ecomfe/zrender |
| tslib | 2.8.1, 2.3.0 | 0BSD | https://github.com/microsoft/tslib |

The locked ECharts and zrender dependencies also use tslib 2.3.0, alongside
the frontend's tslib 2.8.1 dependency.

The production frontend build generates `3rdpartylicenses.txt` with license
texts and notices extracted from bundled dependencies. The frontend Docker
image includes this file at `/usr/share/nginx/html/3rdpartylicenses.txt`,
alongside the browser assets. Preserve this file when distributing the
frontend, together with any additional notices required by the included
components. The table above is a summary, not a substitute for those texts.

## Backend dependencies

The backend and workers use the following additional libraries. Versions follow
the declarations in `backend/pom.xml` and its module POMs, including dependency
management inherited from Spring Boot 4.0.2. The table records the licenses
declared in published Maven POMs, including inherited license metadata.
Java Annotations API's alternative licenses and Classpath exception are
detailed in its version-specific upstream license linked below.

| Component | Version | License | Upstream project or license |
| --- | --- | --- | --- |
| Spring Boot and starters | 4.0.2 | Apache-2.0 | https://github.com/spring-projects/spring-boot |
| Spring Framework | 7.0.3 | Apache-2.0 | https://github.com/spring-projects/spring-framework |
| Spring Security | 7.0.2 | Apache-2.0 | https://github.com/spring-projects/spring-security |
| Spring Data JPA and Redis | 4.0.2 | Apache-2.0 | https://spring.io/projects/spring-data |
| Jackson Databind (`tools.jackson.core:jackson-databind`) | 3.0.4 | Apache-2.0 | https://github.com/FasterXML/jackson-databind |
| Hibernate Envers (`org.hibernate.orm:hibernate-envers`) | 7.2.1.Final | Apache-2.0 | https://github.com/hibernate/hibernate-orm/blob/7.2.1/LICENSE.txt |
| gRPC Java (`grpc-netty-shaded`, `grpc-protobuf`, `grpc-services`, `grpc-stub`) | 1.83.1 | Apache-2.0 | https://github.com/grpc/grpc-java |
| Protocol Buffers (`com.google.protobuf:protobuf-java`) | 4.33.2 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| PostgreSQL JDBC (`org.postgresql:postgresql`) | 42.7.9 | BSD-2-Clause | https://jdbc.postgresql.org/about/license.html |
| MariaDB Connector/J (`org.mariadb.jdbc:mariadb-java-client`) | 3.5.7 | LGPL-2.1-or-later | https://github.com/mariadb-corporation/mariadb-connector-j |
| Microsoft JDBC Driver for SQL Server (`com.microsoft.sqlserver:mssql-jdbc`) | 13.2.1.jre11 | MIT | https://github.com/microsoft/mssql-jdbc |
| Oracle JDBC (`com.oracle.database.jdbc:ojdbc11`) | 23.9.0.25.07 | Oracle Free Use Terms and Conditions (FUTC) | https://www.oracle.com/downloads/licenses/oracle-free-license.html |
| Playwright for Java (`com.microsoft.playwright:playwright`) | 1.62.0 | Apache-2.0 | https://github.com/microsoft/playwright-java |
| Zip4j (`net.lingala.zip4j:zip4j`) | 2.11.5 | Apache-2.0 | https://github.com/srikanth-lingala/zip4j |
| ZXing (`com.google.zxing:core`, `com.google.zxing:javase`) | 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing |
| Java Annotations API (`javax.annotation:javax.annotation-api`) | 1.3.2 | CDDL-1.1 OR (GPL-2.0-only WITH Classpath-exception-2.0) | https://github.com/javaee/javax.annotation/blob/1.3.2/LICENSE |

The Spring Boot starters cover web, WebSocket, validation, Actuator, JPA,
Redis, caching, security, and OAuth2 resource-server support. Their transitive
dependencies retain their own licenses; a starter's license does not replace
the licenses of the libraries it brings in.

The table summarizes the listed libraries' project licenses. Other transitive
dependencies, components bundled inside JARs, and Playwright's driver and
browser distributions may carry additional licenses and notices. Preserve the
applicable license texts, copyright notices, and bundled-component notices
when distributing those artifacts. Dependencies used only for tests and build
plugins are not listed here.

### sqlite-jdbc

Alertify includes the Xerial SQLite JDBC driver
(`org.xerial:sqlite-jdbc:3.53.4.0`). The Xerial JDBC driver is distributed
under the Apache License 2.0 and contains code covered by the BSD 2-Clause
license. The SQLite library bundled with the driver is dedicated to the public
domain. See the upstream project for the complete license and bundled-component
notices:
https://github.com/xerial/sqlite-jdbc

### JGit

Alertify's standard worker includes Eclipse JGit
(`org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r`) to fetch and
analyse Git repositories from alert templates. JGit is distributed under the
Eclipse Distribution License 1.0 (a BSD 3-Clause license). See the upstream
project for the complete license and bundled-component notices:
https://github.com/eclipse-jgit/jgit
