package de.tum.cit.aet.hephaestus.agent.mentor.web;

import de.tum.cit.aet.hephaestus.mentor.MentorEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "A stored question and the turn that answers it")
public record MentorSubmissionDTO(
        @NonNull MentorTurnDTO turn, @NonNull MentorEntryDTO question) {}
