import { ChevronDownIcon } from "lucide-react";

import type {
	ConversationAnswerUse,
	ConversationContextDocument,
	ConversationLink,
	ConversationUsedSource,
} from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

/** The documents that Hephaestus prepares about the person's work, in the person's terms. */
const DOCUMENT_LABELS: Partial<Record<ConversationContextDocument, string>> = {
	USER: "Counts of your work this week and last week",
	WORKSPACE: "Issues assigned to you and reviews you owe",
	RECENT_AUTHORED_WORK: "Your recent pull requests and issues",
	ACTIVITY: "What you did in the last 14 days",
	MERGE_READINESS: "Your open pull requests in detail",
	REVIEW_ATTEMPTS: "Practice review runs on your work",
	RESULTS_HISTORY: "Observations of your work",
	DELIVERED_FEEDBACK: "Practice feedback delivered to you",
	PREPARED_CONVERSATION_FEEDBACK: "Practice feedback prepared for this conversation",
	PRACTICE_PROFILE: "Your Practice profile",
	PRACTICE_CATALOG: "The workspace’s practices and their guidance",
	SLACK_CONVERSATIONS: "Slack threads you took part in",
	OUTLINE_DOCS: "Team documentation",
	WEEKLY_DIGEST: "This weekly digest",
};

function times(count: number, one: string, many: string): string {
	return count === 1 ? one : `${count} ${many}`;
}

/** One source in words: never a tool, a path or an internal ID. A kind that this build does not know is "another source". */
export function usedSourceLabel(source: ConversationUsedSource): string {
	switch (source.kind) {
		case "CONTEXT_DOCUMENT": {
			return (
				(source.document === undefined ? undefined : DOCUMENT_LABELS[source.document]) ??
				"Another document about your work"
			);
		}
		case "REPOSITORY": {
			return source.repository === undefined
				? "A repository"
				: `The repository ${source.repository}`;
		}
		case "REPOSITORY_FILES": {
			const files = times(source.count, "One file", "files");
			return source.repository === undefined ? files : `${files} in ${source.repository}`;
		}
		case "CONTEXT_SEARCH": {
			return times(source.count, "A search of your documents", "searches of your documents");
		}
		case "CONVERSATION_SEARCH": {
			return times(source.count, "A search of earlier messages", "searches of earlier messages");
		}
		default: {
			return "Another source";
		}
	}
}

function citedLabel(links: readonly ConversationLink[]): string | undefined {
	const counts = { RESULT: 0, PROBLEM: 0, FEEDBACK: 0 };
	for (const link of links) {
		if (link.kind === "RESULT" || link.kind === "PROBLEM" || link.kind === "FEEDBACK") {
			counts[link.kind] += 1;
		}
	}
	const parts = [
		counts.RESULT > 0 && times(counts.RESULT, "one observation", "observations"),
		counts.PROBLEM > 0 && times(counts.PROBLEM, "one problem", "problems"),
		counts.FEEDBACK > 0 && times(counts.FEEDBACK, "one piece of feedback", "pieces of feedback"),
	].filter((part): part is string => typeof part === "string");
	return parts.length === 0 ? undefined : `${parts.join(", ")}, shown above`;
}

export interface AnswerUseProps {
	used: ConversationAnswerUse;
	/** What the answer cited, as the message shows it above. */
	links: readonly ConversationLink[];
	/** Names the answer, so that each disclosure on the page has its own accessible name. */
	subject: string;
}

/**
 * What Hephaestus used for one answer (ADR 0157 decision 8), closed until the person opens it. The server derives it
 * from rows; nothing here is model text. It never claims to be complete: every answer also read the conversation.
 */
export function AnswerUse({ used, links, subject }: AnswerUseProps) {
	const cited = citedLabel(links);
	return (
		<Collapsible>
			<CollapsibleTrigger
				aria-label={`What Hephaestus used (${subject})`}
				render={
					<Button variant="ghost" size="xs" className="group -ml-2 w-fit text-muted-foreground" />
				}
			>
				What Hephaestus used
				<ChevronDownIcon
					data-icon="inline-end"
					aria-hidden
					className="transition-transform group-data-[panel-open]:rotate-180"
				/>
			</CollapsibleTrigger>
			<CollapsibleContent>
				<div className="mt-1 flex flex-col gap-3 rounded-lg border p-3 text-sm">
					{used.recorded ? (
						<>
							{cited !== undefined && <UseGroup title="Cited" items={[cited]} />}
							{used.read.length > 0 && (
								<UseGroup title="Read" items={used.read.map(usedSourceLabel)} />
							)}
							{used.couldNotCheck.length > 0 && (
								<UseGroup title="Could not check" items={used.couldNotCheck.map(usedSourceLabel)} />
							)}
							<p className="text-muted-foreground">
								{used.read.length === 0 && used.couldNotCheck.length === 0
									? "Hephaestus opened nothing beyond this conversation and the page you asked from."
									: "This list shows what Hephaestus opened, not everything that it read. It also read this conversation and the page you asked from."}
							</p>
						</>
					) : (
						<p className="text-muted-foreground">
							Hephaestus did not record what this answer used.
						</p>
					)}
				</div>
			</CollapsibleContent>
		</Collapsible>
	);
}

function UseGroup({ title, items }: { title: string; items: readonly string[] }) {
	return (
		<section className="flex flex-col gap-1" aria-label={title}>
			<p className="font-medium text-foreground">{title}</p>
			<ul className="list-disc pl-5 text-muted-foreground">
				{items.map((item) => (
					// The server lists each source once, so its words are unique in a group.
					<li key={item} className="break-words">
						{item}
					</li>
				))}
			</ul>
		</section>
	);
}
