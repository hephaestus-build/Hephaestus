package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import java.util.Optional;
import org.springframework.modulith.NamedInterface;

/** Runs a non-HTTP mentor turn for a verified SCM actor, streaming through the supplied channel. */
@NamedInterface(name = "mentor-chat", propagate = true)
public interface MentorTurnRunner {
    /** The admission a turn runs through, for a caller that must answer before it creates anything. */
    Optional<MentorRefusal> refusal(long workspaceId, long developerId);

    /** The caller must authenticate the developer and verify workspace membership before submitting the turn. */
    void run(MentorTurnRequest request, MentorChannel channel, long developerId);
}
