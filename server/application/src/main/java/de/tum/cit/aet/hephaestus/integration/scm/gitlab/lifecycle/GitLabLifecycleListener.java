package de.tum.cit.aet.hephaestus.integration.scm.gitlab.lifecycle;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import org.springframework.stereotype.Component;

/**
 * GitLab marker. GitLab has no install/uninstall webhook surface — Connections are
 * created via the admin REST flow + token validation. All methods inherit the
 * interface's default no-op.
 */
@Component
public class GitLabLifecycleListener implements IntegrationLifecycleListener {

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }
}
