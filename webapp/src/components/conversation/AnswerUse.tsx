import { ChevronDownIcon } from "lucide-react";

import type { ConversationAnswerUse, ConversationUsedSource } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

/** The documents that Hephaestus prepares about the person's work, in the person's terms. */
const DOCUMENT_LABELS: Readonly<Record<string, string>> = {
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

/** The name of a known document. Any other string, from a newer server or not, gets the neutral name. */
function documentLabel(document: string | undefined, count: number): string {
	const known =
		document !== undefined && Object.hasOwn(DOCUMENT_LABELS, document)
			? DOCUMENT_LABELS[document]
			: undefined;
	return (
		known ?? times(count, "Another document about your work", "other documents about your work")
	);
}

/**
 * Sources of one kind in words: never a tool, a path or a repository name, because a name could outlive the person's
 * access. A kind that this build does not know is "Other sources".
 */
export function usedSourceLabel(source: ConversationUsedSource): string {
	switch (source.kind) {
		case "CONTEXT_DOCUMENT": {
			return documentLabel(source.document, source.count);
		}
		case "REPOSITORY": {
			return times(source.count, "One repository", "repositories");
		}
		case "FILES": {
			return times(source.count, "One file", "files");
		}
		case "COMMAND": {
			return times(source.count, "One command over these files", "commands over these files");
		}
		case "CONVERSATION_SEARCH": {
			return times(source.count, "A search of earlier messages", "searches of earlier messages");
		}
		default: {
			return "Other sources";
		}
	}
}

/** The answer's first words, so that each disclosure on the page has its own accessible name. */
function opening(text: string): string {
	const words = text.replaceAll(/\s+/gu, " ").trim();
	return words.length <= 48 ? words : `${words.slice(0, 47).trimEnd()}…`;
}

function closingLine(used: ConversationAnswerUse): string {
	const also = used.askedFromPage
		? "this conversation and the page you asked from"
		: "this conversation";
	if (used.read.length === 0 && used.couldNotCheck.length === 0) {
		return `Hephaestus opened nothing beyond ${also}.`;
	}
	return `This list shows what Hephaestus opened, not everything that it read. It also read ${also}.`;
}

export interface AnswerUseProps {
	used: ConversationAnswerUse;
	/** Who wrote the answer and when, as the message names it. */
	subject: string;
	/** The answer's words; their opening tells this disclosure apart from the others on the page. */
	text: string;
}

/**
 * What Hephaestus used for one answer (ADR 0157 decision 8), closed until the person opens it. The server derives it
 * from rows; nothing here is model text. It never claims to be complete: every answer also read the conversation.
 */
export function AnswerUse({ used, subject, text }: AnswerUseProps) {
	return (
		<Collapsible>
			<CollapsibleTrigger
				aria-label={`What Hephaestus used (${subject}: ${opening(text)})`}
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
					{used.cited && (
						<UseGroup
							title="Cited"
							items={["The observations and feedback that this answer links to"]}
						/>
					)}
					{used.recorded ? (
						<>
							{used.read.length > 0 && (
								<UseGroup title="Read" items={used.read.map(usedSourceLabel)} />
							)}
							{used.couldNotCheck.length > 0 && (
								<UseGroup title="Could not check" items={used.couldNotCheck.map(usedSourceLabel)} />
							)}
							<p className="text-muted-foreground">{closingLine(used)}</p>
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
					// The server lists each kind of source once, so its words are unique in a group.
					<li key={item} className="break-words">
						{item}
					</li>
				))}
			</ul>
		</section>
	);
}
