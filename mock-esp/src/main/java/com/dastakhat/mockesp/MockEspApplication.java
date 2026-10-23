package com.dastakhat.mockesp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The MOCK eSign provider.
 *
 * <p><b>This is a simulation.</b> It imitates the shape of the CCA eSign API 2.1 exchange
 * (a signed XML request with the document hash, an OTP page and a signed XML response with a
 * one time certificate and a CMS signature) but it never contacts UIDAI or a real eSign
 * service provider, it never asks for an Aadhaar number and it has no legal standing.</p>
 *
 * <p>The provider holds no secrets and no user data. Its protection is the origin allowlist
 * and the strict validation of every request, so the security auto configuration of the
 * shared library is switched off.</p>
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        ManagementWebSecurityAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class MockEspApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockEspApplication.class, args);
    }
}
