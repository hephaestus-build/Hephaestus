package de.tum.cit.aet.hephaestus.scenario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A record that a scenario plants, with its canary.
 *
 * <p>A team record is readable by every member of its home. An owner-only record is readable only by its owner. A
 * record that travels is also readable in each place it travels to. A derived record is readable only where each of
 * its sources is: erasing a source erases what rests on it. The canary is a token derived from the ID, so it is the
 * same in every run and appears nowhere by chance.
 */
public record ScenarioRecord(
        String id,
        Person owner,
        Place home,
        boolean ownerOnly,
        Set<Place> travelsTo,
        List<String> sources,
        String text) {

    public ScenarioRecord {
        travelsTo = Set.copyOf(travelsTo);
        sources = List.copyOf(sources);
    }

    public static ScenarioRecord team(String id, Person owner, Place home, String text) {
        return new ScenarioRecord(id, owner, home, false, Set.of(), List.of(), text);
    }

    public static ScenarioRecord ownerOnly(String id, Person owner, Place home, String text) {
        return new ScenarioRecord(id, owner, home, true, Set.of(), List.of(), text);
    }

    /** This record, also readable in {@code place}. */
    public ScenarioRecord travellingTo(Place place) {
        Set<Place> places = new HashSet<>(travelsTo);
        places.add(place);
        return new ScenarioRecord(id, owner, home, ownerOnly, places, sources, text);
    }

    /** This record, derived from the record with ID {@code source}. */
    public ScenarioRecord derivedFrom(String source) {
        List<String> derived = new ArrayList<>(sources);
        derived.add(source);
        return new ScenarioRecord(id, owner, home, ownerOnly, travelsTo, derived, text);
    }

    public String canary() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return "canary-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }

    /**
     * The text that a subject stores and reads: the record's text with its canary. A subject must carry this text,
     * never {@link #text()} alone, or the canary detectors cannot see where it goes.
     */
    public String content() {
        return text + " [" + canary() + "]";
    }
}
