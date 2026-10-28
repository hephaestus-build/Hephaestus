package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * The capacity that the models in it share at one provider endpoint, for example one GPU gateway. Every worker counts
 * the calls in flight to its models against one limit, whichever workspace makes them. A model in no pool is not
 * limited. GLOBAL (not tenant-scoped): only the instance admin puts a model into a pool, also a workspace's own model.
 */
@Entity
@Table(
        name = "llm_call_pool",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_llm_call_pool_slug",
                        columnNames = {"slug"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
// Writes only changed columns, so an admin's edit never overwrites the cooldown a worker set meanwhile.
@DynamicUpdate
public class LlmCallPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Also the {@code pool} label of the pool's metrics. */
    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** The most calls in flight at once; {@code null} does not limit them, and only the cooldown applies. */
    @Nullable
    @Column(name = "max_concurrent_calls")
    private Integer maxConcurrentCalls;

    /** The calls that only Heph turns may use: a review call needs fewer than the maximum minus these in flight. */
    @ColumnDefault("0")
    @Column(name = "interactive_reserve", nullable = false)
    private int interactiveReserve;

    @Nullable
    @Column(name = "requests_per_minute")
    private Integer requestsPerMinute;

    @Nullable
    @Column(name = "tokens_per_minute")
    private Long tokensPerMinute;

    /** Conservative input-plus-output bound for one call, certified against the provider model's context limit. */
    @Nullable
    @Column(name = "tokens_per_call")
    private Long tokensPerCall;

    /** The provider asked for no call before this time, with its last rate limit answer. */
    @Nullable
    @Column(name = "no_call_before")
    private Instant noCallBefore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Nullable
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
