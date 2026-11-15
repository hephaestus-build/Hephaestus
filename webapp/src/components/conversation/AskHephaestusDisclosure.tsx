import { cn } from "cn";

export interface AskHephaestusDisclosureProps {
	/** Lets the composer name this line as its description, so a screen reader reads it on focus. */
	id?: string;
	/** The workspace that uses Hephaestus here. Absent while its name is not read yet. */
	workspaceName?: string;
	className?: string;
}

/**
 * Says, for as long as the conversation shows, that the reader talks with an AI system (EU AI Act
 * Art. 50(1); 50(5) asks for it clearly, at the first interaction at the latest, and accessibly). It
 * also names the workspace that uses Hephaestus, so the reader knows which workspace's work it
 * answers about. The "AI" badges beside Hephaestus's name are a visual cue, not this disclosure.
 */
export function AskHephaestusDisclosure({
	id,
	workspaceName,
	className,
}: AskHephaestusDisclosureProps) {
	const workspace =
		workspaceName === undefined ? "this workspace" : `the ${workspaceName} workspace`;
	return (
		<p id={id} className={cn("text-center text-xs text-balance text-muted-foreground", className)}>
			Hephaestus is an AI system that {workspace} uses. Its answers can be wrong, so check anything
			important before you rely on it.
		</p>
	);
}
