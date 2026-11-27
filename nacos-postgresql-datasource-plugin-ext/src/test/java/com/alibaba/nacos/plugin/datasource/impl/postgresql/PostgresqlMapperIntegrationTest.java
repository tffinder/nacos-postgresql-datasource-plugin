/*
 * Copyright 1999-2022 Alibaba Group Holding Ltd.
 * Licensed under the Apache License, Version 2.0.
 */
package com.alibaba.nacos.plugin.datasource.impl.postgresql;

import com.alibaba.nacos.common.spi.NacosServiceLoader;
import com.alibaba.nacos.plugin.datasource.MapperManager;
import com.alibaba.nacos.plugin.datasource.constants.ContextConstant;
import com.alibaba.nacos.plugin.datasource.constants.DatabaseTypeConstant;
import com.alibaba.nacos.plugin.datasource.constants.FieldConstant;
import com.alibaba.nacos.plugin.datasource.dialect.DatabaseDialect;
import com.alibaba.nacos.plugin.datasource.dialect.PostgresqlDatabaseDialect;
import com.alibaba.nacos.plugin.datasource.mapper.Mapper;
import com.alibaba.nacos.plugin.datasource.model.MapperContext;
import com.alibaba.nacos.plugin.datasource.model.MapperResult;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes mapper output against a real PostgreSQL server, not a SQL parser. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresqlMapperIntegrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        String schema = new String(Files.readAllBytes(Paths.get("src/main/resources/schema/nacos-pg.sql")),
                StandardCharsets.UTF_8);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(schema);
            statement.execute("CREATE TABLE config_info_beta (LIKE config_info INCLUDING ALL); "
                    + "ALTER TABLE config_info_beta ADD COLUMN beta_ips VARCHAR(1024)");
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    @Order(1)
    void discoversAllMappersAndPostgresqlDialect() {
        List<Mapper> mappers = NacosServiceLoader.load(Mapper.class).stream()
                .filter(mapper -> DatabaseTypeConstant.POSTGRESQL.equals(mapper.getDataSource()))
                .collect(Collectors.toList());
        assertEquals(9, mappers.size());
        for (Mapper mapper : mappers) {
            assertNotNull(MapperManager.instance(false).findMapper(mapper.getDataSource(), mapper.getTableName()));
        }
        assertTrue(NacosServiceLoader.load(DatabaseDialect.class).stream()
                .anyMatch(PostgresqlDatabaseDialect.class::isInstance));
        assertEquals("NOW()", new ConfigInfoMapperByPostgresql().getFunction("NOW()"));
    }

    @Test
    @Order(2)
    void insertsAndSearchesConfiguration() throws Exception {
        ConfigInfoMapperByPostgresql mapper = new ConfigInfoMapperByPostgresql();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(mapper.insert(Arrays.asList(
                        "data_id", "group_id", "content", "tenant_id", "app_name", "md5", "gmt_modified@NOW()")))) {
            statement.setString(1, "integration.properties");
            statement.setString(2, "TEST");
            statement.setString(3, "version=0");
            statement.setString(4, "");
            statement.setString(5, "demo");
            statement.setString(6, "md5-0");
            assertEquals(1, statement.executeUpdate());
        }
        for (int i = 1; i <= 3; i++) {
            MapperContext updateContext = new MapperContext();
            updateContext.putUpdateParameter(FieldConstant.CONTENT, "version=" + i);
            updateContext.putUpdateParameter(FieldConstant.MD5, "md5-" + i);
            updateContext.putUpdateParameter(FieldConstant.SRC_IP, "127.0.0.1");
            updateContext.putUpdateParameter(FieldConstant.SRC_USER, "integration-test");
            updateContext.putUpdateParameter(FieldConstant.APP_NAME, "demo");
            updateContext.putUpdateParameter(FieldConstant.C_DESC, "integration test");
            updateContext.putUpdateParameter(FieldConstant.C_USE, "test");
            updateContext.putUpdateParameter(FieldConstant.EFFECT, "test");
            updateContext.putUpdateParameter(FieldConstant.TYPE, "properties");
            updateContext.putUpdateParameter(FieldConstant.C_SCHEMA, "");
            updateContext.putUpdateParameter(FieldConstant.ENCRYPTED_DATA_KEY, "");
            updateContext.putWhereParameter(FieldConstant.DATA_ID, "integration.properties");
            updateContext.putWhereParameter(FieldConstant.GROUP_ID, "TEST");
            updateContext.putWhereParameter(FieldConstant.TENANT_ID, "");
            updateContext.putWhereParameter(FieldConstant.MD5, "md5-" + (i - 1));
            assertEquals(1, update(mapper.updateConfigInfoAtomicCas(updateContext)));
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO config_info (data_id, group_id, tenant_id, app_name, content, type) "
                    + "SELECT data_id || '.yaml', group_id, tenant_id, app_name, content, 'yaml' FROM config_info "
                    + "WHERE type = 'properties'");
        }
        MapperContext context = new MapperContext(0, 10);
        context.putWhereParameter(FieldConstant.TENANT_ID, "");
        context.putWhereParameter(FieldConstant.DATA_ID, "%integration%");
        context.putWhereParameter(FieldConstant.GROUP_ID, "TEST");
        context.putWhereParameter(FieldConstant.CONTENT, "%version%");
        try (Row row = query(mapper.findConfigInfoBaseLikeFetchRows(context))) {
            assertTrue(row.result.next());
            assertEquals("integration.properties", row.result.getString("data_id"));
            assertEquals(2, row.rows());
        }
        context.putWhereParameter(FieldConstant.TYPE, new String[]{"properties"});
        try (Row row = query(mapper.findConfigInfoLike4PageFetchRows(context))) {
            assertEquals(1, row.rows());
            assertTrue(row.sql.contains("type IN (?)"));
        }
        MapperContext exact = new MapperContext(0, 10);
        exact.putWhereParameter(FieldConstant.TENANT_ID, "");
        exact.putWhereParameter(FieldConstant.DATA_ID, "integration.properties");
        exact.putWhereParameter(FieldConstant.GROUP_ID, "TEST");
        exact.putWhereParameter(FieldConstant.APP_NAME, "demo");
        exact.putWhereParameter(FieldConstant.CONTENT, "%version%");
        try (Row row = query(mapper.findConfigInfo4PageFetchRows(exact))) {
            assertTrue(row.sql.contains("ORDER BY id"));
            assertEquals(1, row.rows());
        }
        try (Row row = query(mapper.findConfigInfoByAppFetchRows(exact))) {
            assertEquals(2, row.rows());
        }
    }

    @Test
    @Order(3)
    void honorsNeedContentAndUsesPostgresqlGrayPagination() throws Exception {
        ConfigInfoMapperByPostgresql mapper = new ConfigInfoMapperByPostgresql();
        MapperContext context = new MapperContext(0, 10);
        context.putWhereParameter(FieldConstant.ID, 0L);
        context.putContextParameter(ContextConstant.NEED_CONTENT, "false");
        try (Row row = query(mapper.findAllConfigInfoFragment(context))) {
            assertTrue(row.result.next());
            assertFalse(row.columns().contains("content"));
            assertTrue(row.columns().contains("encrypted_data_key"));
        }
        context.putContextParameter(ContextConstant.NEED_CONTENT, "true");
        try (Row row = query(mapper.findAllConfigInfoFragment(context))) {
            assertTrue(row.result.next());
            assertTrue(row.columns().contains("content"));
        }
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO config_info_gray (data_id,group_id,tenant_id,gray_name,gray_rule,content,md5) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, "integration.properties");
            statement.setString(2, "TEST");
            statement.setString(3, "");
            statement.setString(4, "canary");
            statement.setString(5, "{\"type\":\"beta\",\"expr\":\"true\"}");
            statement.setString(6, "gray-content");
            statement.setString(7, "gray-md5");
            statement.executeUpdate();
        }
        try (Row row = query(new ConfigInfoGrayMapperByPostgresql()
                .findAllConfigInfoGrayForDumpAllFetchRows(new MapperContext(0, 10)))) {
            assertTrue(row.sql.contains("OFFSET 0 LIMIT 10"));
            assertTrue(row.result.next());
            assertEquals("canary", row.result.getString("gray_name"));
            assertEquals("", row.result.getString("encrypted_data_key"));
        }
    }

    @Test
    @Order(4)
    void returnsNacos255HistoryColumnsAndCleansOldHistory() throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO his_config_info (id,data_id,group_id,tenant_id,content,op_type,publish_type,gray_name,ext_info) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, 1L);
            statement.setString(2, "integration.properties");
            statement.setString(3, "TEST");
            statement.setString(4, "");
            statement.setString(5, "old-content");
            statement.setString(6, "I");
            statement.setString(7, "formal");
            statement.setString(8, "");
            statement.setString(9, "{\"source\":\"test\"}");
            statement.executeUpdate();
        }
        HistoryConfigInfoMapperByPostgresql mapper = new HistoryConfigInfoMapperByPostgresql();
        MapperContext context = new MapperContext(0, 10);
        context.putWhereParameter(FieldConstant.DATA_ID, "integration.properties");
        context.putWhereParameter(FieldConstant.GROUP_ID, "TEST");
        context.putWhereParameter(FieldConstant.TENANT_ID, "");
        try (Row row = query(mapper.pageFindConfigHistoryFetchRows(context))) {
            assertTrue(row.result.next());
            for (String column : Arrays.asList("publish_type", "gray_name", "ext_info")) {
                assertTrue(row.columns().contains(column));
            }
            assertEquals("{\"source\":\"test\"}", row.result.getString("ext_info"));
        }
        MapperContext cleanup = new MapperContext();
        cleanup.putWhereParameter(FieldConstant.START_TIME, new Timestamp(System.currentTimeMillis() + 60000));
        cleanup.putWhereParameter(FieldConstant.LIMIT_SIZE, 100);
        assertEquals(1, update(mapper.removeConfigHistory(cleanup)));
    }

    @Test
    @Order(5)
    void runsGroupCapacityAndTagQueries() throws Exception {
        GroupCapacityMapperByPostgresql mapper = new GroupCapacityMapperByPostgresql();
        MapperContext insert = new MapperContext();
        insert.putUpdateParameter(FieldConstant.GROUP_ID, "TEST");
        insert.putUpdateParameter(FieldConstant.QUOTA, 0);
        insert.putUpdateParameter(FieldConstant.MAX_SIZE, 1024);
        insert.putUpdateParameter(FieldConstant.MAX_AGGR_COUNT, 10);
        insert.putUpdateParameter(FieldConstant.MAX_AGGR_SIZE, 1024);
        insert.putUpdateParameter(FieldConstant.GMT_CREATE, new Timestamp(System.currentTimeMillis()));
        insert.putUpdateParameter(FieldConstant.GMT_MODIFIED, new Timestamp(System.currentTimeMillis()));
        insert.putWhereParameter(FieldConstant.GROUP_ID, "TEST");
        assertEquals(1, update(mapper.insertIntoSelectByWhere(insert)));
        try (Row row = query(mapper.select(insert))) {
            assertTrue(row.result.next());
            assertEquals(2, row.result.getInt("usage"));
        }
        insert.putWhereParameter(FieldConstant.USAGE, 100);
        assertEquals(1, update(mapper.incrementUsageByWhereQuotaEqualZero(insert)));
        insert.putUpdateParameter(FieldConstant.QUOTA, 100);
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE group_capacity SET quota = ? WHERE group_id = ?")) {
            statement.setInt(1, 100);
            statement.setString(2, "TEST");
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(1, update(mapper.incrementUsageByWhereQuotaNotEqualZero(insert)));
        assertEquals(1, update(mapper.decrementUsageByWhere(insert)));
        assertEquals(1, update(mapper.updateUsageByWhere(insert)));
        try (Row row = query(mapper.select(insert))) {
            assertTrue(row.result.next());
            assertEquals(2, row.result.getInt("usage"));
        }
        MapperContext page = new MapperContext();
        page.putWhereParameter(FieldConstant.ID, 0L);
        page.setPageSize(10);
        try (Row row = query(mapper.selectGroupInfoBySize(page))) {
            assertEquals(1, row.rows());
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO config_tags_relation (id,tag_name,data_id,group_id,tenant_id) "
                    + "SELECT id,'production',data_id,group_id,tenant_id FROM config_info LIMIT 1");
        }
        MapperContext tags = new MapperContext(0, 10);
        tags.putWhereParameter(FieldConstant.TENANT_ID, "");
        tags.putWhereParameter(FieldConstant.DATA_ID, "integration.properties");
        tags.putWhereParameter(FieldConstant.TAG_ARR, new String[]{"production", "staging"});
        try (Row row = query(new ConfigTagsRelationMapperByPostgresql().findConfigInfo4PageFetchRows(tags))) {
            assertTrue(row.result.next());
            assertEquals("integration.properties", row.result.getString("data_id"));
        }
    }

    @Test
    @Order(6)
    void runsTenantCapacityQueries() throws Exception {
        TenantCapacityMapperByPostgresql mapper = new TenantCapacityMapperByPostgresql();
        MapperContext context = new MapperContext();
        context.putUpdateParameter(FieldConstant.TENANT_ID, "");
        context.putUpdateParameter(FieldConstant.QUOTA, 0);
        context.putUpdateParameter(FieldConstant.MAX_SIZE, 1024);
        context.putUpdateParameter(FieldConstant.MAX_AGGR_COUNT, 10);
        context.putUpdateParameter(FieldConstant.MAX_AGGR_SIZE, 1024);
        context.putUpdateParameter(FieldConstant.GMT_CREATE, new Timestamp(System.currentTimeMillis()));
        context.putUpdateParameter(FieldConstant.GMT_MODIFIED, new Timestamp(System.currentTimeMillis()));
        context.putWhereParameter(FieldConstant.TENANT_ID, "");
        assertEquals(1, update(mapper.insertTenantCapacity(context)));
        try (Row row = query(mapper.select(context))) {
            assertTrue(row.result.next());
            assertEquals(2, row.result.getInt("usage"));
        }
        MapperContext page = new MapperContext();
        page.putWhereParameter(FieldConstant.ID, 0L);
        page.putWhereParameter(FieldConstant.LIMIT_SIZE, 10);
        page.setPageSize(10);
        try (Row row = query(mapper.getCapacityList4CorrectUsage(page))) {
            assertEquals(1, row.rows());
        }
        context.putUpdateParameter(FieldConstant.GMT_MODIFIED, new Timestamp(System.currentTimeMillis()));
        assertEquals(1, update(mapper.correctUsage(context)));
        context.putWhereParameter(FieldConstant.USAGE, 100);
        context.putUpdateParameter(FieldConstant.GMT_MODIFIED, new Timestamp(System.currentTimeMillis()));
        assertEquals(1, update(mapper.incrementUsageWithDefaultQuotaLimit(context)));
        context.putUpdateParameter(FieldConstant.GMT_MODIFIED, new Timestamp(System.currentTimeMillis()));
        assertEquals(1, update(mapper.decrementUsage(context)));
        try (Row row = query(mapper.select(context))) {
            assertTrue(row.result.next());
            assertEquals(2, row.result.getInt("usage"));
        }
    }

    private static int update(MapperResult mapperResult) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, mapperResult)) {
            return statement.executeUpdate();
        }
    }

    private static Row query(MapperResult mapperResult) throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            PreparedStatement statement = prepare(connection, mapperResult);
            return new Row(connection, statement, statement.executeQuery(), mapperResult.getSql());
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private static PreparedStatement prepare(Connection connection, MapperResult mapperResult) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(mapperResult.getSql(),
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        List<Object> parameters = mapperResult.getParamList();
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
        return statement;
    }

    private static final class Row implements AutoCloseable {
        private final Connection connection;
        private final PreparedStatement statement;
        private final ResultSet result;
        private final String sql;

        private Row(Connection connection, PreparedStatement statement, ResultSet result, String sql) {
            this.connection = connection;
            this.statement = statement;
            this.result = result;
            this.sql = sql;
        }

        private Set<String> columns() throws SQLException {
            ResultSetMetaData metadata = result.getMetaData();
            Set<String> result = new HashSet<>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                result.add(metadata.getColumnLabel(i));
            }
            return result;
        }

        private int rows() throws SQLException {
            int count = 0;
            result.beforeFirst();
            while (result.next()) {
                count++;
            }
            return count;
        }

        @Override
        public void close() throws SQLException {
            result.close();
            statement.close();
            connection.close();
        }
    }
}
