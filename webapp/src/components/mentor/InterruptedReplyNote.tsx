import { AlertCircleIcon } from "lucide-react";

import { Marker, MarkerContent, MarkerIcon } from "@/components/ui/marker";

/** Under a reply the server stored as interrupted, in every surface that shows a conversation. */
export function InterruptedReplyNote() {
	return (
		<Marker>
			<MarkerIcon>
				<AlertCircleIcon />
			</MarkerIcon>
			<MarkerContent>
				This reply was interrupted before it finished, so it is incomplete. Ask again for a full
				answer.
			</MarkerContent>
		</Marker>
	);
}
