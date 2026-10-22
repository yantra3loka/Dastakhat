package com.dastakhat.system;

import com.dastakhat.pki.SealKeyService;
import com.dastakhat.users.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the development sender and loads the development seal key on start up.
 *
 * <p>The account is only a convenience for local development. It is documented in the README
 * and can be switched off with {@code dastakhat.seed.enabled=false}.</p>
 */
@Component
public class DevelopmentBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentBootstrap.class);

    private final AuthService auth;
    private final SealKeyService sealKeys;

    public DevelopmentBootstrap(AuthService auth, SealKeyService sealKeys) {
        this.auth = auth;
        this.sealKeys = sealKeys;
    }

    @Override
    public void run(ApplicationArguments args) {
        sealKeys.load();
        var user = auth.seedDevelopmentUser();
        if (user != null) {
            log.info("Development sender ready: {}", user.getEmail());
        }
    }
}
