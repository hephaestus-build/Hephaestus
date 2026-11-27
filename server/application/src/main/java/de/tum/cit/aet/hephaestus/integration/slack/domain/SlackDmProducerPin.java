package de.tum.cit.aet.hephaestus.integration.slack.domain;

import de.tum.cit.aet.hephaestus.mentor.spi.Producer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Pins one verified Slack DM to its producer across edits, deletions, replay and cutover. It carries no text. */
@Entity
@Table(
        name = "slack_dm_producer_pin",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_slack_dm_producer_pin_source",
                        columnNames = {"workspace_id", "connection_id", "channel_id", "source_ts"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlackDmProducerPin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(nullable = false)
    private long workspaceId;

    @Column(nullable = false)
    private long connectionId;

    @Column(nullable = false, length = 64)
    private String teamId;

    @Column(nullable = false, length = 64)
    private String channelId;

    @Column(nullable = false, length = 17)
    private String sourceTs;

    @Column(length = 17)
    private @Nullable String threadRoot;

    @Column(length = 64)
    private @Nullable String author;

    private @Nullable Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Producer producer;

    public SlackDmProducerPin(
            long workspaceId,
            long connectionId,
            String teamId,
            String channelId,
            String sourceTs,
            @Nullable String threadRoot,
            @Nullable String author,
            @Nullable Long accountId,
            Producer producer) {
        this.workspaceId = workspaceId;
        this.connectionId = connectionId;
        this.teamId = teamId;
        this.channelId = channelId;
        this.sourceTs = sourceTs;
        this.threadRoot = threadRoot;
        this.author = author;
        this.accountId = accountId;
        this.producer = producer;
    }
}
