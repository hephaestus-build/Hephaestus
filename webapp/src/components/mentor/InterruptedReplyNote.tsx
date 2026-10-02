import { AlertCircle } from "lucide-react";

/** Under a reply the server stored as interrupted, in every surface that shows a conversation. */
export function InterruptedReplyNote() {
	return (
		<p className="flex items-center gap-2 text-sm text-muted-foreground">
			<AlertCircle aria-hidden className="size-4 shrink-0" />
			This reply was interrupted before it finished, so it is incomplete. Ask again for a full
			answer.
		</p>
	);
}
