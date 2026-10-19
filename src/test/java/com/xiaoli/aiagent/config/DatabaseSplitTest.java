package com.xiaoli.aiagent.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseSplitTest {
    @Test void mappersUseBusinessDatabaseAndVectorsUseTheirOwnDatabase() {
        String id=UUID.randomUUID().toString();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(DatabaseConfig.class).withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:business-"+id, "spring.datasource.driver-class-name=org.h2.Driver",
                        "app.vector-store.enabled=true", "app.vector-datasource.url=jdbc:h2:mem:vector-"+id,
                        "app.vector-datasource.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var business=context.getBean("businessDataSource",DataSource.class);
                    var vector=context.getBean("vectorDataSource",DataSource.class);
                    assertNotSame(business,vector);
                    assertSame(business,context.getBean(DataSource.class));
                    assertSame(business,context.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getDataSource());
                    assertSame(vector,context.getBean("vectorJdbcTemplate",JdbcTemplate.class).getDataSource());
                });
    }
}
