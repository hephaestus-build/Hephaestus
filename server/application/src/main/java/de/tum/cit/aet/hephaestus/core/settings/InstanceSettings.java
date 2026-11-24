package de.tum.cit.aet.hephaestus.core.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Singleton instance-wide settings row (id = {@link #SINGLETON_ID}). Deliberately not
 * workspace-scoped — listed in {@code WorkspaceScopedTables.GLOBAL_TABLES}.
 */
@Entity
@Table(name = "instance_settings")
@Getter
@Setter
@NoArgsConstructor
public class InstanceSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "public_activity_allowed")
    private @Nullable Boolean publicActivityAllowed;

    /** Whether the public usage page answers (ADR 0153). Null: the configured default decides. */
    @Column(name = "public_usage_allowed")
    private @Nullable Boolean publicUsageAllowed;

    /**
     * What an instance admin withdrew from the public usage page, in the stored form of {@code PublicUsageState}. Only
     * ever grows: a withdrawal is final.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "public_usage_withdrawn", nullable = false, columnDefinition = "jsonb")
    @ColumnDefault("jsonb_build_array()")
    private List<String> publicUsageWithdrawn = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    @ColumnDefault("0")
    private long version;

    @Column(name = "silent_mode_engaged", nullable = false)
    @ColumnDefault("true")
    private boolean silentModeEngaged;

    /** Why the brake was engaged; cleared on release. */
    @Column(name = "silent_mode_reason", length = 500)
    private @Nullable String silentModeReason;

    @Column(name = "silent_mode_changed_at")
    private Instant silentModeChangedAt;

    /** Stable account reference of the administrator; old display-login attribution is not migrated. */
    @Column(name = "silent_mode_changed_by_account_id")
    private @Nullable Long silentModeChangedByAccountId;

    static InstanceSettings failSafeDefault() {
        InstanceSettings settings = new InstanceSettings();
        settings.setId(SINGLETON_ID);
        settings.setSilentModeEngaged(true);
        return settings;
    }
}
