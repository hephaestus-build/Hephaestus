import type { ConversationAnswerUse, ConversationUsedSource } from "@/api/types.gen";

import { AnswerUseDisclosure, UseGroup } from "./AnswerUseDisclosure";

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
			return times(source.count, "One command", "commands");
		}
		case "CONVERSATION_SEARCH": {
			return times(source.count, "A search of earlier messages", "searches of earlier messages");
		}
		default: {
			return "Other sources";
		}
	}
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
	/** When the answer was written, to the second. */
	answeredAt: Date;
	/** The answer's words; their opening and close tell this disclosure apart from the others on the page. */
	text: string;
}

/**
 * What Hephaestus used for one answer (ADR 0157 decision 8), closed until the person opens it. The server derives it
 * from rows; nothing here is model text. It never claims to be complete: every answer also read the conversation.
 */
export function AnswerUse({ used, answeredAt, text }: AnswerUseProps) {
	return (
		<AnswerUseDisclosure answeredAt={answeredAt} text={text}>
			{used.cited && (
				<UseGroup
					title="Cited"
					items={["The observations, problems and feedback that this answer links to"]}
				/>
			)}
			{used.recorded ? (
				<>
					{used.read.length > 0 && <UseGroup title="Read" items={used.read.map(usedSourceLabel)} />}
					{used.couldNotCheck.length > 0 && (
						<UseGroup title="Could not check" items={used.couldNotCheck.map(usedSourceLabel)} />
					)}
					<p className="text-muted-foreground">{closingLine(used)}</p>
				</>
			) : (
				<p className="text-muted-foreground">Hephaestus did not record what this answer used.</p>
			)}
		</AnswerUseDisclosure>
	);
}
