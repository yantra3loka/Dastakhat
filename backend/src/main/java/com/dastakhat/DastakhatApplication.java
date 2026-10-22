package com.dastakhat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Dastakhat - mini e-signature platform.
 *
 * <p>The API server is the only component that talks to the database. Signers never get an
 * account: they use a single use, expiring token that is stored hashed.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DastakhatApplication {

    public static void main(String[] args) {
        SpringApplication.run(DastakhatApplication.class, args);
    }
}
