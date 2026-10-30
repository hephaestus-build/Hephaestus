import { formatDistance } from "date-fns";
import type { ReactNode } from "react";

import type { MentorEntry, MentorThreadSummary } from "@/api/types.gen";
import { useNow } from "@/components/common/use-now";
import { Button } from "@/components/ui/button";
import { Marker, MarkerContent } from "@/components/ui/marker";
import { hasText } from "@/lib/text";

import { ConversationMessage } from "./ConversationMessage";
import type { ConversationTimelineProps, TimelineActions } from "./ConversationTimeline";
import { ConversationTurnStatus } from "./ConversationTurnStatus";
import { MessageActions } from "./MessageActions";
import { MessageStatus, type MessageState } from "./MessageStatus";
import { dayLabel, type TimelineRow, type TurnOutcome } from "./timeline-rows";
import { WeeklyDigestCard } from "./WeeklyDigestCard";

type RowProps = Pick<
	ConversationTimelineProps,
	"developer" | "actions" | "threads" | "workspaceSlug" | "onCopy"
>;

function copyText(entry: Pick<MentorEntry, "text" | "links">): string {
	return [entry.text, ...entry.links.map(({ text }) => text)].filter(hasText).join("\n\n");
}

/**
 * "3 replies · Last reply 2 hours ago", opening the thread with its heading in focus. Its name adds
 * the message, so two summaries that read the same stay apart in a screen reader's list of buttons.
 */
function ThreadSummary({
	summary,
	subject,
	open,
	panelId,
	onOpen,
}: {
	summary: MentorThreadSummary;
	/** As `MessageActions` takes it. */
	subject: string;
	open: boolean;
	panelId: string;
	onOpen: () => void;
}) {
	const now = useNow();
	const replies = summary.replyCount === 1 ? "1 reply" : `${summary.replyCount} replies`;
	const lastReply =
		summary.lastReplyAt === undefined
			? undefined
			: `· Last reply ${formatDistance(summary.lastReplyAt, now, { addSuffix: true })}`;
	const shown = lastReply === undefined ? replies : `${replies} ${lastReply}`;
	return (
		<Button
			variant="link"
			size="inline"
			// On a line of its own, so its target is 24 px tall (WCAG 2.5.8).
			className="min-h-6 self-start"
			aria-label={`${shown} (${subject})`}
			aria-expanded={open}
			aria-controls={open ? panelId : undefined}
			onClick={onOpen}
		>
			{replies}
			{lastReply !== undefined && (
				<span className="font-normal text-muted-foreground">{lastReply}</span>
			)}
		</Button>
	);
}

function stateOfOutcome(outcome: TurnOutcome, actions: TimelineActions | undefined): MessageState {
	if (outcome.status === "STOPPED") {
		return { kind: "stopped" };
	}
	// The line sits under the turn's last message, which is Hephaestus's when it wrote part of an answer
	// before it failed; Try again asks the question again either way.
	const { question } = outcome;
	const ask = actions?.ask;
	return {
		kind: "failed",
		reason: outcome.error ?? "Hephaestus could not answer this message.",
		onTryAgain: ask && !question.deleted ? () => ask.onTryAgain(question) : undefined,
	};
}

function messageActions(
	entry: MentorEntry,
	subject: string,
	{ actions, threads, onCopy }: RowProps,
	onDelete: (entry: MentorEntry) => void,
): ReactNode {
	if (entry.deleted || entry.kind === "DIGEST") {
		return undefined;
	}
	const topLevel = entry.parentEntryId === undefined;
	return (
		<MessageActions
			subject={subject}
			text={copyText(entry)}
			onCopy={onCopy}
			vote={
				actions && entry.kind === "HEPH"
					? { value: entry.vote, onChange: (vote) => actions.onVote(entry, vote) }
					: undefined
			}
			onReply={
				actions?.ask && threads && topLevel ? () => threads.onOpen(entry.id, "composer") : undefined
			}
			onDelete={actions && entry.kind === "USER" ? () => onDelete(entry) : undefined}
		/>
	);
}

export function DayMarker({ date }: { date: Date }) {
	const now = new Date(useNow());
	return (
		<Marker variant="separator">
			<MarkerContent>{dayLabel(date, now)}</MarkerContent>
		</Marker>
	);
}

/** A row's message, over the day that the next row opens. */
function ClosesDay({
	nextDay,
	children,
}: {
	nextDay: Date | undefined;
	children: ReactNode;
}): ReactNode {
	if (nextDay === undefined) {
		return children;
	}
	return (
		<div className="flex flex-col gap-4">
			{children}
			<DayMarker date={nextDay} />
		</div>
	);
}

/** One row of a timeline: a stored message, an unsent one, or Hephaestus's answer in progress. */
export function ConversationTimelineRow({
	row,
	nextDay,
	subject,
	stopLabel,
	props,
	onDelete,
}: {
	row: TimelineRow;
	nextDay: Date | undefined;
	/** As `MessageActions` takes it. */
	subject: string;
	/** Names the running answer's Stop on its status line. Absent where the composer offers Stop. */
	stopLabel: string | undefined;
	props: RowProps;
	onDelete: (entry: MentorEntry) => void;
}) {
	const { developer, actions, threads, workspaceSlug, onCopy } = props;
	switch (row.kind) {
		case "entry": {
			const { entry, queued, outcome, notFullyInSlack } = row;
			const summary = entry.thread;
			const canReply =
				actions?.ask !== undefined && threads !== undefined && entry.parentEntryId === undefined;
			return (
				<ClosesDay nextDay={nextDay}>
					<ConversationMessage
						message={entry}
						developer={developer}
						grouped={row.grouped}
						actions={messageActions(entry, subject, props, onDelete)}
						workspaceSlug={workspaceSlug}
					>
						{entry.kind === "DIGEST" && !entry.deleted && (
							<WeeklyDigestCard
								digest={entry.digest}
								workspaceSlug={workspaceSlug}
								onReplyInThread={canReply ? () => threads.onOpen(entry.id, "composer") : undefined}
							/>
						)}
						{summary && summary.replyCount > 0 && threads && (
							<ThreadSummary
								summary={summary}
								subject={subject}
								open={threads.openParentId === entry.id}
								panelId={threads.panelId}
								onOpen={() => threads.onOpen(entry.id, "heading")}
							/>
						)}
						{queued && (
							<MessageStatus
								subject={subject}
								state={{
									kind: "queued",
									next: queued.next,
									onStop: actions ? () => actions.onStop(queued.turn.id) : undefined,
								}}
							/>
						)}
						{notFullyInSlack === true && (
							<MessageStatus subject={subject} state={{ kind: "notFullyInSlack" }} />
						)}
						{outcome && (
							<MessageStatus subject={subject} state={stateOfOutcome(outcome, actions)} />
						)}
					</ConversationMessage>
				</ClosesDay>
			);
		}
		case "pending": {
			const { message } = row;
			const ask = actions?.ask;
			// A message the server did not store keeps its words: Copy them, or Delete it from this device.
			const failedActions =
				message.status === "failed" ? (
					<MessageActions
						subject={subject}
						text={message.text}
						onCopy={onCopy}
						onDelete={actions ? () => actions.onDiscardSend(message.requestId) : undefined}
					/>
				) : undefined;
			return (
				<ClosesDay nextDay={nextDay}>
					<ConversationMessage
						message={{
							kind: "USER",
							surface: "APP",
							text: message.text,
							createdAt: message.createdAt,
							deleted: false,
							links: [],
						}}
						developer={developer}
						grouped={row.grouped}
						actions={failedActions}
					>
						{message.status === "failed" && (
							<MessageStatus
								subject={subject}
								state={{
									kind: "notSent",
									reason: message.reason,
									onRetry: ask ? () => ask.onRetrySend(message.requestId) : undefined,
								}}
							/>
						)}
					</ConversationMessage>
				</ClosesDay>
			);
		}
		case "live": {
			const { turn } = row;
			return (
				<ConversationTurnStatus
					turn={turn}
					stop={
						actions && stopLabel !== undefined
							? { label: stopLabel, onStop: () => actions.onStop(turn.id) }
							: undefined
					}
				/>
			);
		}
	}
}
