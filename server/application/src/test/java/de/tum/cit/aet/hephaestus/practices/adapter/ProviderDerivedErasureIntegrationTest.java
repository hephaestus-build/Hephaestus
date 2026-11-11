package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.practices.spi.DocumentFeedbackErasure;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each integration's erasure removes the practice results derived from its own work, and the feedback on them, and
 * nothing of the other integrations (ADR 0024): SCM through its mirror erasure, Slack and Outline through their ports.
 */
class ProviderDerivedErasureIntegrationTest extends BaseIntegrationTest {
    private static final ItemKind DOCUMENT = new ItemKind("docs.document");
    private static final ItemKind PULL_REQUEST = new ItemKind("scm.pull_request");
    private static final ItemKind THREAD = new ItemKind("chat.thread");

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ItemRepository items;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DocumentFeedbackErasure documents;

    @Autowired
    private ConversationFeedbackErasure conversations;

    private Scenario scenario;
    private Map<ItemKind, Derived> derived;

    private record Derived(UUID result, UUID feedback) {}

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        // The practice reviews pull requests; a result can still name work of another kind, as erasure must see.
        scenario = data.scenario(PULL_REQUEST, DefinitionTestFixtures.definition());
        derived = Map.of(
                PULL_REQUEST, derive(scenario),
                DOCUMENT, derive(on(DOCUMENT)),
                THREAD, derive(on(THREAD)));
    }

    @Test
    void shouldEraseOnlyTheDocumentRowsWhenOutlineDisconnects() {
        assertErases(DOCUMENT, ws -> documents.eraseAllDocumentsForWorkspace(ws));
    }

    @Test
    void shouldEraseOnlyThePullRequestRowsWhenTheScmMirrorIsErased() {
        assertErases(
                PULL_REQUEST,
                ws -> transactions.executeWithoutResult(status -> events.publishEvent(new ScmMirrorErasedEvent(ws))));
    }

    @Test
    void shouldEraseOnlyTheThreadRowsWhenSlackIsDisconnected() {
        assertErases(THREAD, ws -> conversations.eraseAllConversationForWorkspace(ws));
    }

    private void assertErases(ItemKind erasedKind, Consumer<Long> erasure) {
        erasure.accept(scenario.workspace().getId());

        derived.forEach((kind, rows) -> {
            boolean kept = !kind.equals(erasedKind);
            assertThat(exists("result", rows.result())).as("result on %s", kind).isEqualTo(kept);
            assertThat(exists("feedback", rows.feedback()))
                    .as("feedback on %s", kind)
                    .isEqualTo(kept);
        });
    }

    private Scenario on(ItemKind kind) {
        var item = new Item();
        item.setWorkspaceId(scenario.workspace().getId());
        item.setKind(kind);
        item.setSourceId(ThreadLocalRandom.current().nextLong(1000000000000L, Long.MAX_VALUE));
        item.setCreatedAt(Instant.now().minusSeconds(3600));
        items.saveAndFlush(item);
        return new Scenario(scenario.workspace(), scenario.account(), scenario.practice(), item);
    }

    private Derived derive(Scenario on) {
        Result result = data.result(on, ResultOutcome.NOT_MET, Instant.now());
        long ws = on.workspace().getId();
        UUID feedback = UUID.randomUUID();
        jdbc.sql("INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,"
                        + "created_at,delivered_at)"
                        + " VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','DELIVERED','Review this.',:at,:at)")
                .param("id", feedback)
                .param("ws", ws)
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", on.account().getId())
                .param("at", Timestamp.from(Instant.now()))
                .update();
        jdbc.sql("INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)"
                        + " VALUES(:id,:ws,:feedback,:result,:developer,0)")
                .param("id", FeedbackIds.basis(feedback, result.getId(), null))
                .param("ws", ws)
                .param("feedback", feedback)
                .param("result", result.getId())
                .param("developer", on.account().getId())
                .update();
        return new Derived(result.getId(), feedback);
    }

    private boolean exists(String table, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
