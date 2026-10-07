package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.io.BufferedReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reuses source owners' live access checks; frozen quote verification never substitutes for current consent. */
@Component
public class CitedSourceAccess {
    private final ConversationThreadProjection conversations;
    private final DocumentProjection documents;
    private final ReviewRepositoryPreparer repositories;
    private final ReviewHistoryContentSource history;
    private final JobEvidenceFiles files;
    private final ObjectMapper mapper;
    private final ReviewMemberAiPolicy memberPolicy;
    private final MemberAiPreferences preferences;
    private final WorkspaceMembershipRepository memberships;

    public CitedSourceAccess(
            ConversationThreadProjection conversations,
            DocumentProjection documents,
            ReviewRepositoryPreparer repositories,
            @Lazy ReviewHistoryContentSource history,
            JobEvidenceFiles files,
            ObjectMapper mapper,
            ReviewMemberAiPolicy memberPolicy,
            MemberAiPreferences preferences,
            WorkspaceMembershipRepository memberships) {
        this.conversations = conversations;
        this.documents = documents;
        this.repositories = repositories;
        // History invokes evidence authorization itself. Resolve its shared visibility checks at use, not bean
        // construction.
        this.history = history;
        this.files = files;
        this.mapper = mapper;
        this.memberPolicy = memberPolicy;
        this.preferences = preferences;
        this.memberships = memberships;
    }

    public boolean permitsReviewResult(AgentJob job) {
        return memberPolicy.allowsResult(job);
    }

    public void bind(AgentJob job, ObjectNode citation, String digest) {
        String path = citation.path("artifactPath").asString();
        if (path.startsWith("context/people/")) {
            long person = sourceNumber(path.split("/", -1)[2]);
            if (!memberPolicy.allowsPerson(job, person))
                throw new JobDeliveryException("The cited person no longer permits this processor");
        }
        var reference = mapper.createObjectNode();
        var records = reference.putArray("records");
        if ((path.startsWith("inputs/history/") || path.equals("context/project_inventory.json"))) {
            throw new JobDeliveryException("Cite the canonical source record, not a composed view");
        }
        if (path.equals("context/document.md") || path.equals("context/document.json")) {
            long id = Objects.requireNonNull(job.getMetadata())
                    .path("docs_document_id")
                    .asLong(-1);
            records.addObject().put("type", "document").put("id", id);
        } else if (path.equals("context/conversation_thread.json")) {
            JsonNode payload = files.inspect(
                            job,
                            path,
                            digest,
                            input -> mapper.reader()
                                    .without(StreamReadFeature.AUTO_CLOSE_SOURCE)
                                    .readTree(input))
                    .orElseThrow(() -> new JobDeliveryException("The cited conversation is unavailable"));
            for (JsonNode message : payload.path("messages")) {
                records.addObject()
                        .put("type", "chat")
                        .put("channel", payload.path("channel").asString(""))
                        .put("message", message.path("ts").asString(""));
            }
            if (records.isEmpty()) throw new JobDeliveryException("The cited conversation has no source identity");
        } else if (path.startsWith("context/chat/")
                || (path.startsWith("context/people/") && path.endsWith(".jsonl"))) {
            int first = citation.path("startLine").asInt();
            int last = citation.path("endLine").asInt(first);
            var selected = files.inspect(job, path, digest, input -> {
                        var rows = mapper.createArrayNode();
                        var reader = new BufferedReader(input);
                        for (int line = 1; line <= last; line++) {
                            String content = reader.readLine();
                            if (content == null) break;
                            if (line < first) continue;
                            JsonNode record = mapper.readTree(content);
                            if (record == null || !record.isObject())
                                throw new JobDeliveryException("Invalid cited source record");
                            var entry = rows.addObject();
                            if (path.startsWith("context/chat/")) {
                                entry.put("type", "chat")
                                        .put("channel", record.path("channel").asString())
                                        .put("message", record.path("ts").asString());
                            } else {
                                entry.put("type", path.endsWith("observations.jsonl") ? "observation" : "feedback")
                                        .put("id", record.path("id").asString())
                                        .put("person", sourceNumber(path.split("/", -1)[2]));
                            }
                        }
                        return rows;
                    })
                    .orElseThrow(() -> new JobDeliveryException("The cited folder record is unavailable"));
            records.addAll(selected);
            if (records.isEmpty()) throw new JobDeliveryException("The cited folder record has no source identity");
        } else if (path.startsWith("context/people/") && path.endsWith("/person.json")) {
            records.addObject().put("type", "person").put("person", sourceNumber(path.split("/", -1)[2]));
        } else if (path.startsWith("context/docs/")) {
            String[] parts = path.split("/", -1);
            if (parts.length != 4 || !parts[3].endsWith(".md")) throw new JobDeliveryException("Invalid document path");
            String sourceId = files.inspect(job, path, digest, input -> {
                        var reader = new BufferedReader(input);
                        for (int line = 0; line < 4; line++) {
                            String content = reader.readLine();
                            if (content == null) break;
                            if (content.startsWith("source_id: ")) {
                                JsonNode identity = mapper.readTree(content.substring("source_id: ".length()));
                                if (identity != null
                                        && identity.isString()
                                        && !identity.asString().isBlank()) return identity.asString();
                            }
                        }
                        throw new JobDeliveryException("The cited document has no source identity");
                    })
                    .orElseThrow(() -> new JobDeliveryException("The cited document is unavailable"));
            records.addObject()
                    .put("type", "docs")
                    .put("id", sourceId)
                    .put("collection", decode(parts[2]))
                    .put("slug", decode(parts[3].substring(0, parts[3].length() - 3)));
        } else if (path.startsWith("context/scm/")
                || path.startsWith("repos/")
                || citation.path("sourceKind").asString("").startsWith("scm.")) {
            String[] parts = path.split("/", -1);
            String repo =
                    path.startsWith("repos/") ? parts[1] : path.startsWith("context/scm/") ? parts[2] : "reviewed";
            long id = repo.equals("reviewed")
                    ? Objects.requireNonNull(job.getMetadata())
                            .path("repository_id")
                            .asLong(-1)
                    : sourceNumber(repo);
            records.addObject().put("type", "repository").put("id", id);
        } else {
            citation.remove("sourceReference");
            return;
        }
        citation.set("sourceReference", reference);
        if (!permits(job.getWorkspace().getId(), citation, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)) {
            throw new JobDeliveryException("The cited source is no longer permitted");
        }
    }

    public boolean permits(long workspace, JsonNode citation, SourceUsePurpose purpose) {
        return checks(workspace, purpose).permits(citation);
    }

    /**
     * Citations checked against one workspace and purpose, each answered as {@link #permits} answers it. A record
     * cited again takes the answer it got the first time, and the workspace's permitted repositories are read once,
     * so checking every observation of a workspace costs one owner check per distinct source rather than per
     * citation. Built for one read: a source that changes while it is in use is not seen until the next one.
     */
    public Checks checks(long workspace, SourceUsePurpose purpose) {
        return new RecordChecks(workspace, purpose);
    }

    /** Citations checked for one read; see {@link #checks}. Not thread-safe. */
    @FunctionalInterface
    public interface Checks {
        boolean permits(JsonNode citation);
    }

    private final class RecordChecks implements Checks {
        private final long workspace;
        private final SourceUsePurpose purpose;
        private final Map<String, Boolean> recordAnswers = new HashMap<>();
        private final Map<Long, Boolean> personAnswers = new HashMap<>();
        private final Map<Long, Boolean> aiAnswers = new HashMap<>();
        private @Nullable List<Repository> permittedRepositories;

        private RecordChecks(long workspace, SourceUsePurpose purpose) {
            this.workspace = workspace;
            this.purpose = purpose;
        }

        @Override
        public boolean permits(JsonNode citation) {
            String artifact = citation.path("artifactPath").asString("");
            if (artifact.startsWith("inputs/history/") || artifact.equals("context/project_inventory.json"))
                return false;
            JsonNode reference = citation.path("sourceReference");
            String path = citation.path("artifactPath").asString("");
            if (reference.isMissingNode()) {
                return !path.equals("context/document.md")
                        && !path.equals("context/document.json")
                        && !path.equals("context/conversation_thread.json")
                        && !path.startsWith("context/chat/")
                        && !path.startsWith("context/docs/")
                        && !path.startsWith("context/people/")
                        && !path.startsWith("context/scm/")
                        && !path.startsWith("repos/");
            }
            JsonNode records = reference.path("records");
            if (!records.isArray() || records.isEmpty()) return false;
            for (JsonNode record : records) {
                // A record reads the same wherever it is cited, so its answer is the same too.
                String key = record.toString();
                Boolean answer = recordAnswers.get(key);
                if (answer == null) {
                    answer = permitsRecord(record);
                    recordAnswers.put(key, answer);
                }
                if (!answer) return false;
            }
            return true;
        }

        /** One record's own check; a record that does not parse is not permitted, nor is the citation naming it. */
        private boolean permitsRecord(JsonNode record) {
            try {
                if (record.has("person") && !permitsPerson(record.path("person").asLong())) return false;
                return switch (record.path("type").asString("")) {
                    case "person" -> true;
                    case "chat" ->
                        conversations.isMessageReadable(
                                workspace,
                                record.path("channel").asString(),
                                record.path("message").asString());
                    case "document" ->
                        documents
                                .documentById(workspace, record.path("id").asLong(-1))
                                .filter(doc -> !doc.deleted() && doc.bodyMarkdown() != null)
                                .isPresent();
                    case "docs" ->
                        documents
                                .documentsByReference(
                                        workspace, List.of(record.path("id").asString()))
                                .stream()
                                .anyMatch(doc -> !doc.deleted()
                                        && doc.bodyMarkdown() != null
                                        && record.path("id").asString().equals(doc.sourceId())
                                        && doc.slug().equals(record.path("slug").asString())
                                        && doc.collectionSlug()
                                                .equals(record.path("collection")
                                                        .asString()));
                    case "repository" ->
                        permittedRepositories().stream()
                                .anyMatch(repo ->
                                        repo.getId() == record.path("id").asLong(-1));
                    case "observation", "feedback" ->
                        permitsAi(record.path("person").asLong())
                                && history.permitsHistoryRecord(
                                        workspace,
                                        record.path("type").asString(),
                                        UUID.fromString(record.path("id").asString()),
                                        purpose);
                    default -> false;
                };
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        /** Whether the person a record names still lets AI read about them and is a visible member. */
        private boolean permitsPerson(long person) {
            Boolean answer = personAnswers.get(person);
            if (answer == null) {
                answer = permitsAi(person)
                        && !memberships
                                .findByWorkspace_IdAndUser_Id(workspace, person)
                                .map(member -> member.isHidden())
                                .orElse(true);
                personAnswers.put(person, answer);
            }
            return answer;
        }

        private boolean permitsAi(long person) {
            Boolean answer = aiAnswers.get(person);
            if (answer == null) {
                answer = preferences.forDeveloper(workspace, person).permitsAi();
                aiAnswers.put(person, answer);
            }
            return answer;
        }

        private List<Repository> permittedRepositories() {
            List<Repository> permitted = permittedRepositories;
            if (permitted == null) {
                permitted = repositories.permittedRepositories(workspace);
                permittedRepositories = permitted;
            }
            return permitted;
        }
    }

    private static long sourceNumber(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new JobDeliveryException("Invalid cited source identity", exception);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
