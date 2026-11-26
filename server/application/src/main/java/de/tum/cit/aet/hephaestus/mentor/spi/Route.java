package de.tum.cit.aet.hephaestus.mentor.spi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Where an effect is written, fixed at admission. A top-level question has no outbound parent: its effects post at the top
 * level, and {@code sourceTs} keeps the question as identity and readback cutoff. A thread question pins its verified
 * root. */
public record Route(
        Transport transport,
        String teamId,
        String channelId,
        @Nullable String outboundParent,
        String sourceTs,
        long workspaceId,
        long connectionId,
        long linkedAccountId,
        String nativeAskerId) {

    /** SHA-256 over every field, each with its length, so an observation of one route cannot prove another. */
    public String fingerprint() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(
                    transport.name(),
                    teamId,
                    channelId,
                    outboundParent == null ? "-" : "+" + outboundParent,
                    sourceTs,
                    Long.toString(workspaceId),
                    Long.toString(connectionId),
                    Long.toString(linkedAccountId),
                    nativeAskerId)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }
}
