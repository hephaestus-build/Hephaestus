package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The practice identities that the bundled catalog declares (ADR 0130). A practice version stores the domain-qualified
 * key and the comparability class that this resolves. The checks here refuse a catalog whose identities conflict, so
 * the server cannot start with one and the unit tests fail on one.
 *
 * <p>It is not gated on a runtime role: a practice version can be written outside the server role.
 */
@Component
public class PracticeIdentities {
    static final String CATALOG = "practices/default-catalog.json";
    /** The domain of a practice that no catalog declares. Its key never travels to another workspace. */
    static final String LOCAL = "local";

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*/[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern DIGEST = Pattern.compile("v[0-9]{1,3}:[0-9a-f]{64}");

    /**
     * One identity of the catalog. Each class lists the review digests that judge the same thing; the first digest names
     * the class.
     */
    public record Identity(String key, List<List<String>> classes, List<String> related, List<String> formerly) {
        public Identity {
            classes = classes.stream().map(List::copyOf).toList();
            related = List.copyOf(related);
            formerly = List.copyOf(formerly);
        }
    }

    private final Map<String, Identity> byCatalogSlug;
    private final Map<String, String> kindByCatalogSlug;
    /** Each identity under its key and under each of its former keys. */
    private final Map<String, Identity> byKey;

    public PracticeIdentities() {
        this(bundledCatalog());
    }

    private PracticeIdentities(JsonNode catalog) {
        var bySlug = new HashMap<String, Identity>();
        var kinds = new HashMap<String, String>();
        // Each identity under the subject that an error message names: a practice, or a retired identity.
        var named = new HashMap<String, Identity>();
        for (JsonNode group : catalog.path("groups")) {
            for (JsonNode practice : group.path("practices")) {
                String slug = practice.path("slug").asString("");
                Identity identity = identity("practice " + slug, practice.path("identity"));
                if (bySlug.put(slug, identity) != null) throw invalid("practice " + slug, "its slug appears twice");
                kinds.put(slug, practice.path("kind").asString(""));
                named.put("practice " + slug, identity);
            }
        }
        // A retired identity belongs to a practice that the catalog no longer ships. Its key stays reserved, and its
        // classes still apply to the versions that workspaces keep.
        int position = 0;
        for (JsonNode node : array("the catalog", catalog, "retiredIdentities")) {
            String subject = "retired identity " + position++;
            String kind = text(subject, node, "kind");
            try {
                new ItemKind(kind);
            } catch (IllegalArgumentException notAKind) {
                var refused = invalid(subject, "its kind is no item kind");
                refused.initCause(notAKind);
                throw refused;
            }
            Identity identity = identity(subject, node.path("identity"));
            if (named.put("retired identity " + identity.key(), identity) != null)
                throw invalid("retired identity " + identity.key(), "it appears twice");
        }
        var keys = new HashMap<String, Identity>();
        for (var entry : named.entrySet()) {
            Identity identity = entry.getValue();
            Identity other = keys.putIfAbsent(identity.key(), identity);
            if (other != null) {
                throw invalid(
                        entry.getKey(),
                        !other.classes().equals(identity.classes())
                                ? "another entry claims the identity " + identity.key()
                                        + " with different comparability classes"
                                : "another entry claims the identity " + identity.key());
            }
        }
        for (var entry : named.entrySet()) {
            for (String former : entry.getValue().formerly()) {
                if (keys.putIfAbsent(former, entry.getValue()) != null)
                    throw invalid(entry.getKey(), "its former key " + former + " is claimed already");
            }
        }
        for (var entry : named.entrySet()) {
            for (String related : entry.getValue().related()) {
                Identity target = keys.get(related);
                if (target == null
                        || !target.key().equals(related)
                        || related.equals(entry.getValue().key()))
                    throw invalid(entry.getKey(), "its related key " + related + " names no other catalog identity");
            }
        }
        this.byCatalogSlug = Map.copyOf(bySlug);
        this.kindByCatalogSlug = Map.copyOf(kinds);
        this.byKey = Map.copyOf(keys);
    }

    /** The identities of a catalog document in the bundled catalog's format. */
    public static PracticeIdentities of(JsonNode catalog) {
        return new PracticeIdentities(catalog);
    }

    /**
     * The key for a new practice adopted from the bundled catalog practice with this slug and item kind. An instance
     * entry can change the kind of a bundled practice, and then it judges something else.
     */
    public Optional<String> keyOf(String catalogSlug, String kind) {
        return kind.equals(kindByCatalogSlug.get(catalogSlug))
                ? Optional.ofNullable(byCatalogSlug.get(catalogSlug)).map(Identity::key)
                : Optional.empty();
    }

    /**
     * The key of a new workspace practice: the key of its catalog practice, or a local key for a practice that the
     * bundled catalog does not declare.
     */
    public String keyOf(@Nullable String catalogSlug, String kind, long workspaceId, long practiceId) {
        return (catalogSlug == null ? Optional.<String>empty() : keyOf(catalogSlug, kind))
                .orElseGet(() -> LOCAL + "/" + workspaceId + "/" + practiceId);
    }

    /**
     * The comparability class of a version with this key and review digest: the class that the catalog declares for the
     * digest, or else the digest itself. So an edit that changes the judgement starts a new class unless the catalog says
     * that the new digest judges the same thing.
     */
    public String comparabilityClass(String key, String reviewDigest) {
        Identity identity = byKey.get(key);
        if (identity != null) {
            for (List<String> digests : identity.classes()) {
                if (digests.contains(reviewDigest)) return digests.getFirst();
            }
        }
        return reviewDigest;
    }

    /** Whether a key can travel to another workspace: every key except a local one. */
    public static boolean portable(String key) {
        return !key.startsWith(LOCAL + "/");
    }

    private static Identity identity(String subject, JsonNode node) {
        if (!node.isObject()) throw invalid(subject, "it has no identity");
        String domain = text(subject, node, "domain");
        String name = text(subject, node, "key");
        if (!NAME.matcher(domain).matches() || !NAME.matcher(name).matches())
            throw invalid(subject, "its domain and key need lowercase words with single hyphens");
        if (domain.equals(LOCAL)) throw invalid(subject, "the domain local is reserved for workspace practices");
        // practice_version.identity_key holds at most 160 characters.
        if (domain.length() + 1 + name.length() > 160) throw invalid(subject, "its key is longer than 160 characters");
        var classes = new ArrayList<List<String>>();
        var digests = new HashSet<String>();
        for (JsonNode group : array(subject, node, "classes")) {
            var members = new ArrayList<String>();
            for (JsonNode digest : group) {
                String value = digest.asString("");
                if (!DIGEST.matcher(value).matches())
                    throw invalid(subject, "a class lists something that is no digest");
                if (!digests.add(value)) throw invalid(subject, "the digest " + value + " is in two classes");
                members.add(value);
            }
            if (!group.isArray() || members.isEmpty()) throw invalid(subject, "a class needs a list of digests");
            classes.add(members);
        }
        return new Identity(
                domain + "/" + name, classes, keys(subject, node, "related"), keys(subject, node, "formerly"));
    }

    private static List<String> keys(String subject, JsonNode node, String field) {
        var keys = new ArrayList<String>();
        for (JsonNode key : array(subject, node, field)) {
            String value = key.asString("");
            if (!KEY.matcher(value).matches() || value.startsWith(LOCAL + "/"))
                throw invalid(subject, field + " needs domain-qualified catalog keys");
            keys.add(value);
        }
        return keys;
    }

    private static Iterable<JsonNode> array(String subject, JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) return List.of();
        if (!value.isArray()) throw invalid(subject, field + " needs a list");
        return value;
    }

    private static String text(String subject, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw invalid(subject, "its identity needs a " + field);
        return value.asString();
    }

    private static IllegalArgumentException invalid(String subject, String reason) {
        return new IllegalArgumentException("The catalog identity of " + subject + " is invalid: " + reason + ".");
    }

    private static JsonNode bundledCatalog() {
        try (InputStream input = new ClassPathResource(CATALOG).getInputStream()) {
            return JsonMapper.builder().build().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read the bundled catalog: " + CATALOG, exception);
        }
    }
}
