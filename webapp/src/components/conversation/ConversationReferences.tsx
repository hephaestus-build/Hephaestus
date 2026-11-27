import { RemovableToken } from "@/components/common/RemovableToken";

/** Something a question carries with it, such as a team's work or one of its observations. */
export interface ConversationReference {
	key: string;
	label: string;
}

export interface ConversationReferencesProps {
	references: readonly ConversationReference[];
	/** Removes a reference from the question before it is sent. Nothing is on the server until then. */
	onRemove: (key: string) => void;
}

/**
 * The references a question will carry, each removable before it is sent, so the person sees exactly what goes with
 * their words. It sits in a composer's context row.
 */
export function ConversationReferences({ references, onRemove }: ConversationReferencesProps) {
	if (references.length === 0) {
		return null;
	}
	return (
		<ul aria-label="Sent with your question" className="flex min-w-0 flex-wrap gap-1">
			{references.map((reference) => (
				<li key={reference.key} className="min-w-0">
					<RemovableToken
						label={reference.label}
						removeLabel={`Remove ${reference.label}`}
						onRemove={() => onRemove(reference.key)}
					/>
				</li>
			))}
		</ul>
	);
}
