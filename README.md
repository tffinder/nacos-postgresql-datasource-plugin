# Nacos 2.5.5 PostgreSQL Datasource Plugin

[![Build, test and release](https://github.com/tffinder/nacos-postgresql-datasource-plugin/actions/workflows/release.yml/badge.svg)](https://github.com/tffinder/nacos-postgresql-datasource-plugin/actions/workflows/release.yml)

A standalone PostgreSQL datasource plugin for **Nacos Server 2.5.5**. The `all` JAR includes the PostgreSQL JDBC driver, the base mapper classes and all required SPI descriptors; the Nacos SPI remains `provided` and is not shaded.

[中文说明](README.zh-CN.md)

## Compatibility

| Component | Verified version |
| --- | --- |
| Nacos Server | 2.5.5 (`e3c8420a30f59286cb282856320f58785bb87591`) |
| PostgreSQL | 14+ supported; GitHub Actions tests PostgreSQL 16, local tests PostgreSQL 18 |
| Java | 8+ runtime; CI builds Java 8 bytecode with JDK 17 |
| JDBC driver | PostgreSQL 42.7.8 |

This project started from [nacos-group/nacos-plugin tag 2.5.x.1](https://github.com/nacos-group/nacos-plugin/tree/2.5.x.1), commit `fce4e2277132bbf4b4b192acc40550f384a02305`, and retains its Apache License 2.0 notices. It is not an official Nacos release and is not a claim of compatibility with every Nacos 2.x version. Use the release matching your Nacos minor version and test a staging environment first.

## What was adapted

The upstream 2.5.x.1 source was not sufficient for a reproducible Nacos 2.5.5 installation:

- Maven Central's `nacos-datasource-plugin:2.5.5` artifact exposes SPI interfaces but does not contain the MySQL mapper implementations. The previous implementation inherited those missing classes and could not build independently. All PostgreSQL mappers now use public SPI interfaces directly.
- Gray configuration dumps no longer emit MySQL `LIMIT offset,count` syntax.
- Base configuration search restores its missing `group_id LIKE ?` placeholder.
- `NEED_CONTENT=false` really omits `content`; stable `ORDER BY id` is used for offset pagination.
- History list SQL returns `ext_info`, `publish_type` and `gray_name`, required by Nacos 2.5.5's `HISTORY_ROW_MAPPER`.
- Group-capacity SQL uses PostgreSQL quoting and dialect pagination; tag pagination has deterministic ordering.
- The schema contains no `DROP TABLE` statements.

## Install

1. Download `nacos-postgresql-datasource-plugin-ext-2.5.5.1-all.jar` and `nacos-pg.sql` from the [Releases page](https://github.com/tffinder/nacos-postgresql-datasource-plugin/releases). Verify the SHA-256 checksum.
2. **New database only:** create a dedicated database and run `nacos-pg.sql` as a database owner. The script fails if tables already exist. Never run a fresh-install script on a populated production database.
3. Copy the `all` JAR to `${nacos.home}/plugins/`. Do not add a second PostgreSQL driver or another PostgreSQL mapper plugin.
4. In `conf/application.properties`, configure your database (the password is only an example):

   ```properties
   spring.sql.init.platform=postgresql
   db.num=1
   db.url.0=jdbc:postgresql://127.0.0.1:5432/nacos
   db.user.0=nacos
   db.password.0=replace-with-a-strong-password
   ```

5. Start Nacos normally in standalone or cluster mode. Nacos's startup script supplies `-Dloader.path=${BASE_DIR}/plugins,...`. Verify the logs contain successful `MapperManager` registration for `postgresql` and no SQL exceptions.

For clusters, install the same JAR on every node and point every node at the same external database. This plugin does not migrate data from Derby or MySQL.

### Existing databases and upgrades

**Stop Nacos and take a verified backup before upgrading.** There is no universal upgrade script for every historical PostgreSQL schema. Inspect your existing schema against Nacos 2.5.5's official schema and the shipped schema. In particular, ensure `config_info_gray`, `encrypted_data_key`, and history columns `ext_info`, `publish_type` and `gray_name` exist with compatible types before deploying 2.5.5. Add or modify only the missing objects in a reviewed, version-specific migration, then test on a restored backup. Do not delete or recreate populated tables. The bundled SQL is **not** an upgrade script.

## Build and test

```bash
mvn -B -ntp clean verify
```

The JUnit suite starts a real embedded PostgreSQL instance, initializes the shipped schema, discovers the SPI implementations and executes mapper SQL for configuration CRUD/search, gray dumps, history metadata/cleanup, tags and group capacity. It does not use H2 or SQL-string-only assertions. On Windows the test runtime may need an accessible temporary directory; GitHub Actions runs it on Ubuntu.

The GitHub Actions workflow additionally downloads the official Nacos 2.5.5 distribution, installs the built JAR, starts Nacos with PostgreSQL 16, verifies health and configuration publish/get/update/history/delete, and only then publishes a tag Release. Its verification job downloads the **published** asset, verifies its checksum and repeats the Nacos test with that downloaded JAR.

## Project layout

- `nacos-datasource-plugin-ext-base`: dialect-neutral mapper SQL and dialect manager.
- `nacos-postgresql-datasource-plugin-ext`: PostgreSQL dialect, nine mapper implementations, JDBC driver and SPI descriptors.
- `nacos-postgresql-datasource-plugin-ext/src/main/resources/schema/nacos-pg.sql`: non-destructive fresh-install schema.
- `scripts/`: end-to-end verification used locally and in CI.

## Research scope

For the Nacos 2.x line considered on 2026-11-23, the newest releases were **2.5.5, 2.5.4, 2.5.3, 2.5.2, 2.5.1 and 2.5.0**. Nacos 3.x is outside this project's compatibility target.

Related plugins reviewed:

- [nacos-group/nacos-plugin](https://github.com/nacos-group/nacos-plugin): official plugin sources, tag 2.5.x.1; no usable plugin JAR in that tag's release and the compatibility issues listed above.
- [pig-mesh/nacos-datasource-plugin-pg](https://github.com/pig-mesh/nacos-datasource-plugin-pg): current source targets Nacos 3.x, not this 2.5.5 target.
- [wuchubuzai2018/nacos-datasource-extend-plugins](https://github.com/wuchubuzai2018/nacos-datasource-extend-plugins): based on Nacos 2.2.0 and unmaintained.
- [apegeek/nacos-datasource-plugin-ext](https://github.com/apegeek/nacos-datasource-plugin-ext): based on Nacos 2.2.3.

## License

[Apache License 2.0](LICENSE). Nacos and PostgreSQL are separate projects with their own licenses and trademarks.
