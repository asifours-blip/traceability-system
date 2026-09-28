package com.qhx.back.flow;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 同一组文件与查询用例跑在真实 MySQL 上（验证新表的 MySQL 语法、LIKE 转义、分页 SQL）。只有设置了 MYSQL_IT_URL 才运行。
 */
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_URL", matches = ".+")
class FilesAndQueriesMySqlSmokeTest extends FilesAndQueriesTestBase {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MYSQL_IT_URL"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_IT_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_IT_PASSWORD", ""));
    }
}
