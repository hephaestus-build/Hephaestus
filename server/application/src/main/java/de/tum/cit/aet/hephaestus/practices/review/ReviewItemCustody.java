package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItemCopiedIdentity;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The copy custody of a review item. Its copied identities live in {@code review_item_copied_identity}, one row each,
 * because a capture can copy more people than the bounded {@code detail} holds. The rest stays in
 * {@code detail.custody}. The migration moved every earlier list into the rows and removed it from the detail.
 */
@Component
@RequiredArgsConstructor
public class ReviewItemCustody {
    private static final String COPIED = "copiedIdentities";
    private static final Comparator<PersonCopyIdentity> ORDER = Comparator.comparing(PersonCopyIdentity::providerType)
            .thenComparing(PersonCopyIdentity::providerOrigin)
            .thenComparing(PersonCopyIdentity::subject)
            .thenComparing(identity -> Objects.requireNonNullElse(identity.teamId(), ""));

    private final ReviewItemCopiedIdentityRepository copies;
    private final JsonMapper json;

    /** Sets the member's custody: its copied identities in their table, everything else in {@code detail}. */
    public void record(ReviewItem member, ObjectNode detail, HistoryCustody custody) {
        ObjectNode stored = json.valueToTree(custody);
        stored.remove(COPIED);
        detail.set("custody", stored);
        copies.deleteMember(member.getWorkspaceId(), member.getReviewId(), member.getPracticeId(), member.getItemId());
        copies.saveAll(custody.copiedIdentities().stream()
                .distinct()
                .sorted(ORDER)
                .map(identity -> ReviewItemCopiedIdentity.of(member, identity))
                .toList());
    }

    /** The custody that {@link #record} stored for the member, if it has one. */
    public Optional<HistoryCustody> read(ReviewItem member) {
        return Optional.ofNullable(
                custodies(member.getWorkspaceId(), List.of(member)).get(id(member)));
    }

    /**
     * The stored custody of each member that has one, with its copied identities. One statement reads both, so an
     * erasure that runs at the same time is seen whole or not at all.
     */
    public Map<ReviewItem.Id, HistoryCustody> custodies(long workspaceId, Collection<ReviewItem> members) {
        if (members.isEmpty()) return Map.of();
        var keys = members.stream()
                .map(member -> Map.of(
                        "review_id", member.getReviewId(),
                        "practice_id", member.getPracticeId(),
                        "item_id", member.getItemId()))
                .distinct()
                .toList();
        var stored = new LinkedHashMap<ReviewItem.Id, String>();
        var identities = new HashMap<ReviewItem.Id, List<PersonCopyIdentity>>();
        for (var row : copies.findCustody(workspaceId, json.writeValueAsString(keys))) {
            var member = new ReviewItem.Id(row.getReviewId(), row.getPracticeId(), row.getItemId());
            stored.putIfAbsent(member, row.getCustody());
            var list = identities.computeIfAbsent(member, ignored -> new ArrayList<>());
            if (row.getProviderType() != null)
                list.add(new PersonCopyIdentity(
                        row.getProviderType(),
                        Objects.requireNonNull(row.getProviderOrigin()),
                        Objects.requireNonNull(row.getSubject()),
                        row.getTeamId()));
        }
        var custodies = new HashMap<ReviewItem.Id, HistoryCustody>();
        stored.forEach((member, custody) ->
                custodies.put(member, read(json.readTree(custody), identities.getOrDefault(member, List.of()))));
        return custodies;
    }

    /** The stored custody with the member's copied identities: only the rows. */
    HistoryCustody read(JsonNode stored, List<PersonCopyIdentity> identities) {
        ObjectNode custody = (ObjectNode) stored.deepCopy();
        custody.set(COPIED, json.valueToTree(identities.stream().sorted(ORDER).toList()));
        return json.convertValue(custody, HistoryCustody.class);
    }

    public static ReviewItem.Id id(ReviewItem member) {
        return new ReviewItem.Id(member.getReviewId(), member.getPracticeId(), member.getItemId());
    }
}
