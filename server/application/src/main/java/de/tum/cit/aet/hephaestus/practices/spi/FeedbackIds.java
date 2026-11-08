package de.tum.cit.aet.hephaestus.practices.spi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class FeedbackIds {

    private static final UUID NAMESPACE = UUID.fromString("38bc4319-78c2-5e14-9af1-06bba7c88d8e");

    private FeedbackIds() {}

    public static UUID onWork(long workspaceId, UUID reviewId, long developerId) {
        return named(prefix(workspaceId, reviewId, developerId, FeedbackDestination.ON_WORK) + "package");
    }

    public static UUID practicePage(long workspaceId, UUID reviewId, long developerId, UUID resultId) {
        return named(prefix(workspaceId, reviewId, developerId, FeedbackDestination.PRACTICE_PAGE) + resultId);
    }

    public static UUID conversation(long workspaceId, UUID reviewId, long developerId, Collection<UUID> resultIds) {
        if (resultIds.isEmpty()) {
            throw new IllegalArgumentException("Conversation feedback needs a result basis.");
        }
        String results =
                resultIds.stream().map(UUID::toString).distinct().sorted().collect(Collectors.joining(","));
        return named(prefix(workspaceId, reviewId, developerId, FeedbackDestination.CONVERSATION) + results);
    }

    public static UUID withheld(
            long workspaceId, UUID reviewId, long developerId, FeedbackDestination destination, UUID resultId) {
        return named(prefix(workspaceId, reviewId, developerId, destination) + "withheld/" + resultId);
    }

    public static UUID copy(UUID feedbackId, FeedbackCopyKind kind, int position) {
        if (position < 0) {
            throw new IllegalArgumentException("Copy position must be nonnegative.");
        }
        return named("copy/" + feedbackId + "/" + kind + "/" + position);
    }

    public static UUID correction(UUID rootId, String draftId) {
        return named("correction/" + rootId + "/" + draftId);
    }

    public static UUID basis(UUID feedbackId, UUID resultId, @Nullable UUID problemId) {
        return named("basis/" + feedbackId + "/" + resultId + "/" + (problemId == null ? "-" : problemId));
    }

    public static String writeMarker(UUID copyId) {
        return "<!-- hephaestus-feedback-copy:" + copyId + " -->";
    }

    private static String prefix(long workspaceId, UUID reviewId, long developerId, FeedbackDestination destination) {
        if (workspaceId <= 0 || developerId <= 0) {
            throw new IllegalArgumentException("Workspace and developer ids must be positive.");
        }
        return workspaceId + "/" + reviewId + "/" + developerId + "/" + destination + "/";
    }

    private static UUID named(String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16)
                    .putLong(NAMESPACE.getMostSignificantBits())
                    .putLong(NAMESPACE.getLeastSignificantBits())
                    .array());
            byte[] hash = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required for RFC-4122 UUIDv5.", exception);
        }
    }
}
