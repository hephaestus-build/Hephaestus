package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

public final class ScmReviewFixtures {
    private final JdbcTemplate jdbc;
    private final ItemIndexService items;
    private final PeopleRegistry people;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public ScmReviewFixtures(
            JdbcTemplate jdbc,
            ItemIndexService items,
            PeopleRegistry people,
            TransactionTemplate transactions,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.items = items;
        this.people = people;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    public record Recorded(UUID reviewId, UUID resultId, UUID problemId, long itemId, long developerId) {}

    public Recorded result(long workspaceId, Issue issue, String version, Instant at) {
        return Objects.requireNonNull(transactions.execute(status -> {
            User author = Objects.requireNonNull(issue.getAuthor());
            long developer = people.ensureHuman(
                    new PersonIdentity(
                            Objects.requireNonNull(author.getProvider().getId()),
                            Long.toString(author.getNativeId()),
                            null),
                    author.getName() == null ? author.getLogin() : author.getName());
            jdbc.update(
                    "INSERT INTO"
                            + " membership(workspace_id,account_id,role,hidden,review_selected,public_activity_seen,joined_at)"
                            + " VALUES (?,?,'MEMBER',false,false,false,?) ON CONFLICT DO NOTHING",
                    workspaceId,
                    developer,
                    Timestamp.from(at));
            var repository = items.upsert(new ItemRegistration(
                    workspaceId,
                    ScmItemEvents.REPOSITORY,
                    Objects.requireNonNull(issue.getRepository()).getId(),
                    null,
                    null));
            var item = items.upsert(
                    new ItemRegistration(workspaceId, ScmItemEvents.ISSUE, issue.getId(), repository.itemId(), null));
            long practiceId = sequence("practice");
            long versionId = sequence("practice_version");
            String slug = "fixture-" + UUID.randomUUID();
            var definition = mapper.createObjectNode();
            definition.put("name", "Trackable work");
            definition.putObject("reviewOn").putArray("events").add("scm.issue.updated");
            definition.withObject("/reviewOn").putObject("state");
            definition.putNull("appliesWhen");
            definition.put("about", "AUTHOR");
            var reads = definition.putObject("reads");
            reads.put("contract", "3.0.0");
            reads.putArray("sources")
                    .addObject()
                    .put("source", "scm.issue.core")
                    .put("need", "REQUIRED");
            var reviewedBy = definition.putObject("reviewedBy");
            reviewedBy.put("by", "HEPHAESTUS");
            reviewedBy.putNull("humanReason");
            reviewedBy.putArray("limits");
            definition.put("criteria", "Break work into trackable steps.");
            definition.putNull("script");
            definition.putNull("scan");
            definition.put("lineNotes", false);
            var guidance = definition.putObject("guidance");
            for (String key : new String[] {"whyItMatters", "whatGoodLooksLike", "goingWell", "visual", "guide"})
                guidance.putNull(key);
            jdbc.update(
                    "INSERT INTO"
                            + " practice(id,workspace_id,slug,kind,position,current_version_id,created_at)"
                            + " VALUES (?,?,?,'scm.issue',0,?,?)",
                    practiceId,
                    workspaceId,
                    slug,
                    versionId,
                    Timestamp.from(at));
            jdbc.update(
                    "INSERT INTO"
                            + " practice_version(id,workspace_id,practice_id,number,definition,created_at)"
                            + " VALUES (?,?,?,1,?::jsonb,?)",
                    versionId,
                    workspaceId,
                    practiceId,
                    definition.toString(),
                    Timestamp.from(at));
            UUID review = UUID.randomUUID();
            UUID result = UUID.randomUUID();
            UUID problem = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO"
                            + " review(id,workspace_id,item_id,kind,item_version,origin,requested_by_id,status,rollout_revision,ordered_at,created_at,admitted_at,completed_at,checks)"
                            + " VALUES (?,?,?,'ITEM',?,'REQUEST',?,'COMPLETE',0,?,?,?,?, '{}'::jsonb)",
                    review,
                    workspaceId,
                    item.itemId(),
                    version,
                    developer,
                    Timestamp.from(at),
                    Timestamp.from(at),
                    Timestamp.from(at),
                    Timestamp.from(at));
            jdbc.update(
                    "INSERT INTO"
                            + " review_practice(review_id,practice_id,workspace_id,practice_version_id,status)"
                            + " VALUES (?,?,?,?,'DONE')",
                    review,
                    practiceId,
                    workspaceId,
                    versionId);
            jdbc.update(
                    "INSERT INTO review_item(workspace_id,review_id,practice_id,item_id,item_version,state) VALUES"
                            + " (?,?,?,?,?,'LISTED')",
                    workspaceId,
                    review,
                    practiceId,
                    item.itemId(),
                    version);
            String quote = issue.getBody() == null || issue.getBody().isBlank() ? issue.getTitle() : issue.getBody();
            var citation = mapper.createObjectNode()
                    .put("sourceKind", "scm.issue.core")
                    .put("sourcePath", "inputs/context/description.md")
                    .put("path", "inputs/context/description.md")
                    .put("startLine", 1)
                    .put("endLine", quote.lines().count())
                    .put("quote", quote);
            citation.putObject("verification").put("status", "VERIFIED").put("sourceHash", hash(quote));
            var citations = mapper.createArrayNode().add(citation);
            var evidence = mapper.createObjectNode().set("citations", citations);
            jdbc.update(
                    "INSERT INTO"
                            + " result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,outcome,severity,summary,explanation,evidence,evidence_version,recorded_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,'NOT_MET','MAJOR','Work needs trackable steps','The fixture work"
                            + " contains no trackable steps.',?::jsonb,1,?)",
                    result,
                    workspaceId,
                    review,
                    practiceId,
                    versionId,
                    item.itemId(),
                    version,
                    developer,
                    evidence.toString(),
                    Timestamp.from(at));
            jdbc.update(
                    "INSERT INTO"
                            + " problem(id,workspace_id,result_id,position,severity,summary,explanation,citations,basis)"
                            + " VALUES (?,?,?,0,'MAJOR','Work needs trackable steps','The fixture work contains no"
                            + " trackable steps.',?::jsonb,?::jsonb)",
                    problem,
                    workspaceId,
                    result,
                    citations.toString(),
                    "{\"type\":\"state\",\"explanation\":\"The full issue" + " description has no trackable steps.\"}");
            jdbc.update(
                    "UPDATE review_item SET state='JUDGED',result_id=? WHERE workspace_id=? AND review_id=? AND"
                            + " practice_id=? AND item_id=?",
                    result,
                    workspaceId,
                    review,
                    practiceId,
                    item.itemId());
            return new Recorded(review, result, problem, item.itemId(), developer);
        }));
    }

    public void delivered(long workspaceId, Issue issue, String ref, @Nullable String url, Instant at) {
        transactions.executeWithoutResult(status -> {
            var recorded = result(workspaceId, issue, "fixture:" + UUID.randomUUID(), at);
            UUID feedback = FeedbackIds.onWork(workspaceId, recorded.reviewId(), recorded.developerId());
            UUID copy = FeedbackIds.copy(feedback, FeedbackCopyKind.SUMMARY, 0);
            jdbc.update(
                    "INSERT INTO"
                            + " feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,checks,created_at,delivered_at)"
                            + " VALUES (?,?,?,?,?,'ON_WORK','DELIVERED','Fixture feedback','{}'::jsonb,?,?)",
                    feedback,
                    workspaceId,
                    recorded.reviewId(),
                    recorded.itemId(),
                    recorded.developerId(),
                    Timestamp.from(at),
                    Timestamp.from(at));
            jdbc.update(
                    "INSERT INTO"
                            + " feedback_basis(id,workspace_id,feedback_id,result_id,problem_id,developer_id,ordinal)"
                            + " VALUES (?,?,?,?,?,?,0)",
                    UUID.randomUUID(),
                    workspaceId,
                    feedback,
                    recorded.resultId(),
                    recorded.problemId(),
                    recorded.developerId());
            jdbc.update(
                    "INSERT INTO"
                            + " feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_started_at,write_key,write_marker,external_ref,external_url,created_at,posted_at)"
                            + " VALUES (?,?,?,'SUMMARY',0,'POSTED',?,?,?,?,?,?,?)",
                    copy,
                    workspaceId,
                    feedback,
                    Timestamp.from(at),
                    copy.toString(),
                    FeedbackIds.writeMarker(copy),
                    ref,
                    url,
                    Timestamp.from(at),
                    Timestamp.from(at));
        });
    }

    private long sequence(String table) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence(?, 'id'))", Long.class, table));
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
