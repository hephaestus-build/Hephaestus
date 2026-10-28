import { differenceInCalendarDays, isSameDay } from "date-fns";

import type { MentorEntry, MentorTurn } from "@/api/types.gen";
import { formatWeekdayDay } from "@/lib/dates";
import type { PendingMessage } from "@/lib/mentor-entry";
import { hasText } from "@/lib/text";

/** Messages from one author on one surface this close together read as one turn of speech. */
const GROUP_WINDOW_MS = 5 * 60_000;

/**
 * `key` stays the same while a message moves from sent to stored, or from Heph's live answer to the
 * stored one, so the row keeps its element. `messageId` is what a link and the scroller find it by.
 */
interface RowIdentity {
	key: string;
	messageId: string;
}

export type TimelineRow =
	| (RowIdentity & {
			kind: "entry";
			/** The local day this row opens. */
			day?: Date;
			entry: MentorEntry;
			/** As `HephMessage` takes it. */
			grouped: boolean;
			/** The question waits for an earlier one to finish; `next` when no other waits before it. */
			queued?: { turn: MentorTurn; next: boolean };
			/** How the turn this message belongs to ended, shown under its last message. */
			outcome?: TurnOutcome;
			/** Heph's answer, to a question asked in Slack, that did not fully reach Slack; on the turn's last answer. */
			notFullyInSlack?: boolean;
	  })
	| (RowIdentity & { kind: "pending"; day?: Date; message: PendingMessage; grouped: boolean })
	| (RowIdentity & { kind: "live"; turn: MentorTurn });

const requestKey = (requestId: string) => `request:${requestId}`;
const turnKey = (turnId: string) => `turn:${turnId}`;

/** A turn that ended without its full answer, as its question carries it. */
export interface TurnOutcome {
	status: "STOPPED" | "FAILED";
	/** Why it failed, in words for the developer. */
	error?: string;
	/** The question the turn answered, which Try again asks again. */
	question: MentorEntry;
}

interface TimelineInput {
	entries: MentorEntry[];
	pending: PendingMessage[];
	turns: MentorTurn[];
}

function outcomesOf(entries: MentorEntry[]): Map<string, TurnOutcome> {
	const outcomes = new Map<string, TurnOutcome>();
	for (const question of entries) {
		const { kind, turnId, turnStatus, turnError } = question;
		if (kind !== "USER" || turnId === undefined) {
			continue;
		}
		if (turnStatus === "STOPPED") {
			outcomes.set(turnId, { status: "STOPPED", question });
		} else if (turnStatus === "FAILED") {
			outcomes.set(turnId, {
				status: "FAILED",
				question,
				...(hasText(turnError) ? { error: turnError } : {}),
			});
		}
	}
	return outcomes;
}

interface Speaker {
	author: "developer" | "heph" | "digest";
	surface: MentorEntry["surface"];
	createdAt: Date;
}

const AUTHORS = { USER: "developer", HEPH: "heph", DIGEST: "digest" } as const satisfies Record<
	MentorEntry["kind"],
	Speaker["author"]
>;

function speakerOf(entry: MentorEntry): Speaker {
	return { author: AUTHORS[entry.kind], surface: entry.surface, createdAt: entry.createdAt };
}

function continues(previous: Speaker | undefined, next: Speaker): boolean {
	return (
		previous !== undefined &&
		next.author !== "digest" &&
		previous.author === next.author &&
		previous.surface === next.surface &&
		next.createdAt.getTime() - previous.createdAt.getTime() < GROUP_WINDOW_MS
	);
}

/**
 * The turn in progress in one scope: the running one. A queued turn waits under its question while
 * another runs; with nothing running, the first one is about to start, so it is in progress.
 */
export function liveTurnOf(turns: MentorTurn[]): MentorTurn | undefined {
	return turns.find(({ status }) => status === "RUNNING") ?? turns[0];
}

/** The key a message keeps from the moment it shows: its request, or the turn Heph answers in. */
function keyOf(entry: MentorEntry, firstAnswer: Map<string, string>): string {
	if (entry.kind === "USER" && entry.requestId !== undefined) {
		return requestKey(entry.requestId);
	}
	if (entry.turnId !== undefined && firstAnswer.get(entry.turnId) === entry.id) {
		return turnKey(entry.turnId);
	}
	return entry.id;
}

/**
 * The rows of one scope — the main conversation or a thread — in reading order: stored messages by
 * `seq`, then the developer's unsent ones, then Heph's answer in progress. The first message of each
 * local day carries the day.
 */
export function timelineRows({ entries, pending, turns }: TimelineInput): TimelineRow[] {
	const outcomes = outcomesOf(entries);
	const firstAnswer = new Map<string, string>();
	const lastOfTurn = new Map<string, string>();
	const lastAnswer = new Map<string, string>();
	const notFullyInSlack = new Set<string>();
	for (const entry of entries) {
		if (entry.turnId === undefined) {
			continue;
		}
		lastOfTurn.set(entry.turnId, entry.id);
		if (entry.kind === "HEPH") {
			lastAnswer.set(entry.turnId, entry.id);
			if (!firstAnswer.has(entry.turnId)) {
				firstAnswer.set(entry.turnId, entry.id);
			}
		}
		// The server marks the question asked in Slack; the answer is what did not fully get there. A stream
		// that Slack refused to finish is FAILED too, after Slack showed part of it.
		if (entry.kind === "USER" && entry.slackDelivery === "FAILED") {
			notFullyInSlack.add(entry.turnId);
		}
	}
	// A turn stores its answer as it ends, so a stored answer ends it here before its end arrives.
	const active = turns.filter(({ id }) => !firstAnswer.has(id));
	const live = liveTurnOf(active);
	const waiting = active.filter((turn) => turn !== live);

	const rows: TimelineRow[] = [];
	let previous: Speaker | undefined;
	/** The day `date` opens, if it opens one; a new day also ends any group. */
	const openDay = (date: Date): { day?: Date } => {
		if (previous !== undefined && isSameDay(previous.createdAt, date)) {
			return {};
		}
		previous = undefined;
		return { day: date };
	};

	for (const entry of entries) {
		const speaker = speakerOf(entry);
		const day = openDay(entry.createdAt);
		const queued = waiting.findIndex(
			(turn) => turn.id === entry.turnId || turn.requestId === entry.requestId,
		);
		const queuedTurn = waiting[queued];
		const outcome =
			entry.turnId !== undefined && lastOfTurn.get(entry.turnId) === entry.id
				? outcomes.get(entry.turnId)
				: undefined;
		rows.push({
			kind: "entry",
			key: keyOf(entry, firstAnswer),
			messageId: entry.id,
			...day,
			entry,
			grouped: entry.kind === "USER" && continues(previous, speaker),
			...(entry.kind === "USER" && queuedTurn
				? { queued: { turn: queuedTurn, next: queued === 0 } }
				: {}),
			...(outcome ? { outcome } : {}),
			...(entry.turnId !== undefined &&
			notFullyInSlack.has(entry.turnId) &&
			lastAnswer.get(entry.turnId) === entry.id
				? { notFullyInSlack: true }
				: {}),
		});
		previous = speaker;
	}

	for (const message of pending) {
		const speaker: Speaker = {
			author: "developer",
			surface: "APP",
			createdAt: message.createdAt,
		};
		const key = requestKey(message.requestId);
		rows.push({
			kind: "pending",
			key,
			messageId: key,
			...openDay(message.createdAt),
			message,
			grouped: continues(previous, speaker),
		});
		previous = speaker;
	}

	if (live !== undefined) {
		const key = turnKey(live.id);
		rows.push({ kind: "live", key, messageId: key, turn: live });
	}
	return rows;
}

/** "Today", "Yesterday", or the weekday and date, as a day separator reads it. */
export function dayLabel(date: Date, now: Date): string {
	const days = differenceInCalendarDays(now, date);
	if (days === 0) {
		return "Today";
	}
	if (days === 1) {
		return "Yesterday";
	}
	return formatWeekdayDay(date, now);
}
