# Nacos 2.5.5 PostgreSQL 数据源插件

这是面向 **Nacos Server 2.5.5** 的独立 PostgreSQL 数据源插件。`all` JAR 已包含 PostgreSQL JDBC 驱动、基础 Mapper 和 SPI 描述文件；Nacos SPI 使用 `provided` 依赖，不会重复打包进插件。

[English documentation](README.md)

## 适配范围

| 组件 | 版本 |
| --- | --- |
| Nacos Server | 2.5.5 |
| PostgreSQL | 支持 14+；Actions 使用 16，本机测试使用 18 |
| Java | Java 8+；JDK 17 编译 Java 8 字节码 |
| PostgreSQL JDBC | 42.7.8 |

本项目基于 [nacos-group/nacos-plugin 的 2.5.x.1 标签](https://github.com/nacos-group/nacos-plugin/tree/2.5.x.1)（提交 `fce4e2277132bbf4b4b192acc40550f384a02305`），保留 Apache License 2.0 许可和原有版权声明。它不是 Nacos 官方发布版本，也不表示兼容所有 Nacos 2.x；应使用与 Nacos 次版本匹配的 Release，并先在测试环境验证。

## 相对旧版插件的修复

- 直接使用 Nacos 2.5.5 公开 SPI，不再依赖 Maven Central 工件中不存在的 MySQL Mapper 实现类，也不再要求先构建整套 Nacos。
- 修复灰度配置导出中的 MySQL 分页语法，补齐历史记录 `ext_info`、`publish_type`、`gray_name` 字段。
- 修复配置模糊搜索中缺失的 `group_id LIKE ?` 占位符，正确处理 `NEED_CONTENT=false`。
- 为偏移分页增加稳定排序，修正容量表及标签查询的 PostgreSQL 语法。
- 建库脚本不包含 `DROP TABLE`，已有同名表时会失败而不是清除数据。

## 安装

1. 从 [Releases](https://github.com/tffinder/nacos-postgresql-datasource-plugin/releases) 下载 `nacos-postgresql-datasource-plugin-ext-2.5.5.1-all.jar`、`nacos-pg.sql` 和 `SHA256SUMS`，并验证校验和。
2. **仅限全新的空数据库**：用数据库所有者执行 `nacos-pg.sql`。已有表时脚本会报错；不要在生产数据库上执行全新安装脚本。
3. 将 `all` JAR 放入 `${nacos.home}/plugins/`，不要同时放另一个 PostgreSQL 驱动或 PostgreSQL Mapper 插件。
4. 修改 `conf/application.properties`：

   ```properties
   spring.sql.init.platform=postgresql
   db.num=1
   db.url.0=jdbc:postgresql://127.0.0.1:5432/nacos
   db.user.0=nacos
   db.password.0=请替换为强密码
   ```

5. 按 Nacos 原来的 standalone 或 cluster 方式启动。启动脚本会自动传入 `-Dloader.path=${BASE_DIR}/plugins,...`。检查日志中 `MapperManager` 是否成功注册 `postgresql`，并确认没有 SQL 异常。

集群每个节点都需安装相同 JAR 并连接同一外部数据库。本插件不负责 Derby/MySQL 数据迁移。

### 已有数据库升级

**升级前停止 Nacos，完成并验证数据库备份。** 不提供适用于所有历史版本的通用升级 SQL。请对比现有表结构、Nacos 2.5.5 官方表结构和本项目建库脚本，尤其检查 `config_info_gray`、`encrypted_data_key` 以及历史表的 `ext_info`、`publish_type`、`gray_name`。只添加经审阅、与当前旧版本匹配的变更，并先在备份恢复环境验证。不要删除或重建已有数据的表；本项目的 `nacos-pg.sql` 不是升级脚本。

## 构建与测试

```bash
mvn -B -ntp clean verify
```

测试会启动真正的嵌入式 PostgreSQL，执行建库脚本、发现 SPI，并对配置查询、灰度导出、历史元数据及清理、标签和容量表 SQL 做实际数据库验证，而不是仅比较 SQL 字符串。

GitHub Actions 还会启动官方 Nacos 2.5.5，连接 PostgreSQL 16，验证健康检查及配置发布、读取、修改、历史和删除；通过后才发布 Release。随后另一个任务下载已发布的附件、验证校验和，并使用该附件重新执行 Nacos 验证。

## 近期版本与相关插件调研

截至 2026-11-23，本次调研范围内的 Nacos 2.x 最近版本为 **2.5.5、2.5.4、2.5.3、2.5.2、2.5.1、2.5.0**；Nacos 3.x 不属于本项目适配目标。

- [nacos-group/nacos-plugin](https://github.com/nacos-group/nacos-plugin)：官方插件源码，存在 2.5.x.1 标签，但该标签 Release 没有可直接使用的插件 JAR，源码存在上述兼容问题。
- [pig-mesh/nacos-datasource-plugin-pg](https://github.com/pig-mesh/nacos-datasource-plugin-pg)：当前源码转向 Nacos 3.x，并非本次 2.5.5 适配版本。
- [wuchubuzai2018/nacos-datasource-extend-plugins](https://github.com/wuchubuzai2018/nacos-datasource-extend-plugins)：基于 Nacos 2.2.0，已不维护。
- [apegeek/nacos-datasource-plugin-ext](https://github.com/apegeek/nacos-datasource-plugin-ext)：基于 Nacos 2.2.3。

许可：[Apache License 2.0](LICENSE)。Nacos 与 PostgreSQL 是独立项目，适用各自许可证和商标。
