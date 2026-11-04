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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.jspecify.annotations.Nullable;

/**
 * The capacity that the models in it share at one provider endpoint, for example one GPU gateway. Every worker counts
 * the calls in flight to its models against one limit, whichever workspace makes them. Every model is in a pool: the
 * database gives each model its own, and an instance admin can move models into a shared one. GLOBAL (not
 * tenant-scoped): the rows hold capacity only, and only the instance admin edits them, also a workspace model's.
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

    /** The most calls in flight the instance admin allows; {@code null} leaves the {@link #learnedLimit} uncapped. */
    @Nullable
    @Column(name = "max_concurrent_calls")
    private Integer maxConcurrentCalls;

    /** What {@link LlmCallLimit} learned from the provider. The database sets the first value and workers change it. */
    @ColumnDefault("4")
    @Generated(event = EventType.INSERT)
    @Column(name = "learned_limit", nullable = false, insertable = false, updatable = false)
    private int learnedLimit;

    /** See {@link LlmCallLimit#credit()}. */
    @ColumnDefault("0")
    @Generated(event = EventType.INSERT)
    @Column(name = "limit_credit", nullable = false, insertable = false, updatable = false)
    private int limitCredit;

    /** When the provider's refusal or queue last lowered the {@link #learnedLimit}; {@code null} before the first. */
    @Nullable
    @Column(name = "throttled_at", insertable = false, updatable = false)
    private Instant throttledAt;

    /** See {@link LlmCallDelay#baselineMillis()}. */
    @Nullable
    @Column(name = "baseline_first_token_ms", insertable = false, updatable = false)
    private Integer baselineFirstTokenMs;

    /** See {@link LlmCallDelay#windowMillis()}. */
    @Nullable
    @Column(name = "window_first_token_ms", insertable = false, updatable = false)
    private Integer windowFirstTokenMs;

    /** See {@link LlmCallDelay#samples()}. */
    @ColumnDefault("0")
    @Generated(event = EventType.INSERT)
    @Column(name = "window_samples", nullable = false, insertable = false, updatable = false)
    private int windowSamples;

    /** The pool the database made for one model. It lives and ends with the model and takes no other member. */
    @ColumnDefault("false")
    @Generated(event = EventType.INSERT)
    @Column(name = "dedicated", nullable = false, insertable = false, updatable = false)
    private boolean dedicated;

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
