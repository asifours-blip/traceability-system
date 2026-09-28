package com.qhx.back.flow;

import org.springframework.test.context.TestPropertySource;

/**
 * 业务闭环测试在 H2（MySQL 模式）上运行，CI 默认执行。用例见 BusinessFlowTestBase。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:business_flow_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
})
class BusinessFlowIntegrationTest extends BusinessFlowTestBase {
}
