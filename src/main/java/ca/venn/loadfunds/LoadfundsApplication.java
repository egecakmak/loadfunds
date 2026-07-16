package ca.venn.loadfunds;

import ca.venn.loadfunds.model.velocity.VelocityLimits;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VelocityLimits.class)
public class LoadfundsApplication {

	public static void main(String[] args) {
		SpringApplication.run(LoadfundsApplication.class, args);
	}

}
