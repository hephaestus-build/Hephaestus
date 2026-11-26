package de.tum.cit.aet.hephaestus.account;

import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AccountCurrentDeveloperAdapter implements CurrentDeveloperLookup {
    @Override
    public Optional<Long> currentDeveloperId() {
        return SecurityUtils.getCurrentAccountId();
    }

    @Override
    public long currentDeveloperIdElseThrow() {
        return currentDeveloperId().orElseThrow();
    }
}
