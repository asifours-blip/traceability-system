package com.qhx.back.flow;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 同一组业务闭环用例跑在真实 MySQL 上：启动时执行三份 schema（验证真实 MySQL 语法与可重复执行），再跑全部用例。
 * 只有设置了 MYSQL_IT_URL 才运行，CI 不设置，自动跳过。示例（容器 trace-mysql-test，端口 13306）：
 * MYSQL_IT_URL="jdbc:mysql://127.0.0.1:13306/trace_it?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
 * MYSQL_IT_USER=root MYSQL_IT_PASSWORD=... mvn -B test -Dtest=BusinessFlowMySqlSmokeTest
 */
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_URL", matches = ".+")
class BusinessFlowMySqlSmokeTest extends BusinessFlowTestBase {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("MYSQL_IT_URL"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_IT_USER", "root"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_IT_PASSWORD", ""));
    }
}
