package ca.venn.loadfunds;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LoadfundsApplicationTests {

	@Autowired
	private HikariDataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Environment environment;


	@Test
	void contextLoads() {
	}

	@Test
	void configuresBoundedDatabaseWaits() {
		assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
		assertThat(dataSource.getConnectionTimeout()).isEqualTo(3_000L);
		assertThat(dataSource.getValidationTimeout()).isEqualTo(1_000L);
		assertThat(dataSource.getConnectionInitSql()).isEqualTo("SET LOCK_TIMEOUT 3000");
		assertThat(jdbcTemplate.queryForObject(
			"CALL LOCK_TIMEOUT()",
			Integer.class
		)).isEqualTo(3_000);
	}

	@Test
	void exposesBoundedTomcatWorkerMetrics() {
		assertThat(environment.getProperty("server.tomcat.threads.max", Integer.class)).isEqualTo(200);
		assertThat(environment.getProperty("server.tomcat.threads.min-spare", Integer.class)).isEqualTo(10);
		assertThat(environment.getProperty("server.tomcat.mbeanregistry.enabled", Boolean.class)).isTrue();
	}

}
