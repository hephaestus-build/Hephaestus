import type { SupportShareMessage, SupportShareTurn } from "@/api/types.gen";
import { hasText } from "@/lib/text";

import { diagnosticLines } from "./support-share-words";

export interface SupportShareContentProps {
	/** How Hephaestus ran the question. Absent when the question no longer exists. */
	turn?: SupportShareTurn;
	/** The words that the share shows: empty when it shows the diagnostics only. */
	messages: SupportShareMessage[];
	/** The name of the person who asked, as the admin reads it; "You" in the person's own preview. */
	asker: string;
}

/**
 * Exactly what a support share shows. The person's preview and the admin's reading render this one
 * component, so what the person confirms is what the admin reads.
 */
export function SupportShareContent({ turn, messages, asker }: SupportShareContentProps) {
	return (
		<div className="space-y-4">
			{turn && (
				<dl
					aria-label="How Hephaestus ran this question"
					className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm"
				>
					{diagnosticLines(turn).map((line) => (
						<div key={line.term} className="contents">
							<dt className="text-muted-foreground">{line.term}</dt>
							<dd className="min-w-0 break-words">
								{line.code === true ? (
									<code className="text-xs break-all">{line.value}</code>
								) : (
									line.value
								)}
							</dd>
						</div>
					))}
				</dl>
			)}
			{messages.length > 0 && (
				<ol aria-label="Shared messages" className="space-y-3">
					{messages.map((message) => (
						<li key={message.entryId} className="space-y-1 rounded-lg border p-3">
							<p className="text-xs font-medium text-muted-foreground">
								{message.author === "QUESTION" ? asker : "Hephaestus"}
							</p>
							<p className="text-sm break-words whitespace-pre-wrap">
								{hasText(message.text) ? message.text : "—"}
							</p>
						</li>
					))}
				</ol>
			)}
		</div>
	);
}
