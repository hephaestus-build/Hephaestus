import { cn } from "cn";

export interface WrittenByHephaestusNoteProps {
	className?: string;
}

/** The words of the AI label, also quoted by the user docs. */
export const WRITTEN_BY_HEPHAESTUS = "Written by Hephaestus, an AI system. It can be wrong.";

/**
 * Marks text that Hephaestus wrote, such as an observation, a problem or a piece of feedback, as
 * written by an AI system. It is a sentence, not a badge: one value for every text is not a status
 * (DESIGN.md § Status vocabularies). It names the system, not a model: a practice script can decide
 * an observation in its own code, and no observation records which.
 */
export function WrittenByHephaestusNote({ className }: WrittenByHephaestusNoteProps) {
	return <p className={cn("text-xs text-muted-foreground", className)}>{WRITTEN_BY_HEPHAESTUS}</p>;
}
