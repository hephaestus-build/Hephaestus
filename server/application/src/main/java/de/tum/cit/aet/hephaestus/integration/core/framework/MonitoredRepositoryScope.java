package de.tum.cit.aet.hephaestus.integration.core.framework;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Whether a workspace's {@code repository_to_monitor} row covers a {@code repository} row of the
 * same {@code name_with_owner}.
 *
 * <p>Repositories are unique per {@code (provider_id, name_with_owner)}, but a monitor stores only
 * the name. So a monitor covers a repository only when, besides the name:
 *
 * <ul>
 *   <li>the monitor's native id, once known, is the repository's, and
 *   <li>the repository's provider is the one registered for the workspace's active or paused GitHub
 *       or GitLab connection: the same kind and the connection's configured server URL, or {@link
 *       PersonSourceNamespace}'s default for that kind when it configures none. {@code
 *       SourceProviderNamespaces} registers exactly that spelling, so it is compared as stored.
 * </ul>
 *
 * A workspace without such a connection has no source provider, and its monitors cover nothing.
 *
 * <p>JPQL calls {@value #FUNCTION}{@code (monitor.workspace.id, monitor.nativeId,
 * repository.nativeId, repository.provider.id) = true}; native SQL appends {@link #NATIVE_RTM_R}.
 * Keep the join's name match: it is what the indexes serve.
 */
public final class MonitoredRepositoryScope implements FunctionContributor {

    public static final String FUNCTION = "monitor_covers_repository";

    private static final String SOURCE_PROVIDER =
            " FROM connection monitor_connection JOIN identity_provider monitor_provider ON monitor_provider.type = monitor_connection.kind AND monitor_provider.server_url = monitor_connection.config ->> 'serverUrl' WHERE monitor_connection.kind IN ('GITHUB', 'GITLAB') AND monitor_connection.state IN ('ACTIVE', 'SUSPENDED')";

    /**
     * The predicate over {@code ?1} monitor workspace id, {@code ?2} monitor native id, {@code ?3}
     * repository native id and {@code ?4} repository provider id.
     */
    static final String PATTERN = "((?2 IS NULL OR ?2 = ?3) AND EXISTS (SELECT 1"
            + SOURCE_PROVIDER
            + " AND monitor_connection.workspace_id = ?1 AND monitor_provider.id = ?4))";

    /**
     * Whether repository {@code r} is on the source provider of the workspace that owns monitor
     * {@code rtm}, for native SQL with those aliases.
     */
    public static final String NATIVE_RTM_SOURCE_OF_R = "EXISTS (SELECT 1"
            + SOURCE_PROVIDER
            + " AND monitor_connection.workspace_id = rtm.workspace_id AND monitor_provider.id = r.provider_id)";

    /**
     * Whether provider {@code :providerId} is the source provider of the workspace that owns
     * monitor {@code rtm}.
     */
    public static final String NATIVE_RTM_SOURCE_PROVIDER = "EXISTS (SELECT 1"
            + SOURCE_PROVIDER
            + " AND monitor_connection.workspace_id = rtm.workspace_id AND monitor_provider.id = :providerId)";

    /**
     * {@link #PATTERN} for native SQL that aliases {@code repository_to_monitor} as {@code rtm} and
     * {@code repository} as {@code r}. A unit test keeps it equal to the pattern.
     */
    public static final String NATIVE_RTM_R =
            "((rtm.native_id IS NULL OR rtm.native_id = r.native_id) AND EXISTS (SELECT 1"
                    + SOURCE_PROVIDER
                    + " AND monitor_connection.workspace_id = rtm.workspace_id AND monitor_provider.id = r.provider_id))";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        contributions
                .getFunctionRegistry()
                .registerPattern(
                        FUNCTION,
                        PATTERN,
                        contributions
                                .getTypeConfiguration()
                                .getBasicTypeRegistry()
                                .resolve(StandardBasicTypes.BOOLEAN));
    }
}
