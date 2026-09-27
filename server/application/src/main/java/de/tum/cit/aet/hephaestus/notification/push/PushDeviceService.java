package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers and unregisters the app installations of the signed-in account. */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Push devices belong to an account, which spans workspaces")
public class PushDeviceService {

    private final PushDeviceRepository deviceRepository;
    private final PushProperties properties;

    public PushDeviceService(PushDeviceRepository deviceRepository, PushProperties properties) {
        this.deviceRepository = deviceRepository;
        this.properties = properties;
    }

    public boolean available() {
        return properties.available();
    }

    public boolean isRegistered(Long accountId, @Nullable UUID nativeSessionId, String installationId) {
        return deviceRepository
                .findByInstallationId(installationId)
                .map(device -> device.getAccountId().equals(accountId)
                        && device.getNativeSessionId().equals(nativeSessionId))
                .orElse(false);
    }

    /**
     * Registers an installation for the account and native session making the request. An installation
     * another account registered before — the app was signed out and into a different account — moves
     * with the sign-in, and a token that moved to a new installation leaves its old row behind.
     */
    @Transactional
    public void register(
            Long accountId,
            UUID nativeSessionId,
            String installationId,
            String expoPushToken,
            PushDevice.Platform platform) {
        deviceRepository
                .findByExpoPushToken(expoPushToken)
                .filter(other -> !other.getInstallationId().equals(installationId))
                .ifPresent(other -> {
                    deviceRepository.delete(other);
                    deviceRepository.flush();
                });
        deviceRepository.register(
                UUID.randomUUID(), accountId, installationId, expoPushToken, platform.name(), nativeSessionId);
    }

    @Transactional
    public void unregister(Long accountId, String installationId) {
        deviceRepository.deleteOwned(accountId, installationId);
    }
}
