package com.qhx.back.flow;

import org.springframework.test.context.TestPropertySource;

/**
 * 文件与查询测试在 H2（MySQL 模式）上运行，CI 默认执行。用例见 FilesAndQueriesTestBase。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:files_queries_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
})
class FilesAndQueriesIntegrationTest extends FilesAndQueriesTestBase {
}
