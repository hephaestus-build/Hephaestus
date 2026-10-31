import { ArrowDownIcon, MessageSquareWarningIcon } from "lucide-react";
import {
	type ReactNode,
	type RefObject,
	useEffect,
	useLayoutEffect,
	useRef,
	useState,
} from "react";

import type { MentorEntry, MentorTurn } from "@/api/types.gen";
import { ConfirmDialog } from "@/components/common/ConfirmDialog";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Marker, MarkerContent } from "@/components/ui/marker";
import {
	MessageScroller,
	MessageScrollerButton,
	MessageScrollerContent,
	MessageScrollerItem,
	MessageScrollerProvider,
	MessageScrollerViewport,
	useMessageScroller,
	useMessageScrollerScrollable,
	useMessageScrollerVisibility,
} from "@/components/ui/message-scroller";
import type { PendingMessage } from "@/lib/conversation-entry";
import { hasText } from "@/lib/text";

import { type MessageAuthor, messageSubject } from "./ConversationMessage";
import { DayMarker, ConversationTimelineRow } from "./ConversationTimelineRow";
import { ConversationTimelineSkeleton } from "./ConversationTimelineSkeleton";
import { LEAVE_NOTE, useTurnStatusLabel, useWaitedSeconds } from "./ConversationTurnStatus";
import type { MessageVote } from "./MessageActions";
import { type TimelineRow, timelineRows } from "./timeline-rows";

export type { MessageVote } from "./MessageActions";

/** The older messages above the loaded ones. */
export type EarlierMessages =
	| { status: "none" }
	| { status: "available"; onLoad: () => void }
	| { status: "loading" }
	| { status: "error"; onRetry: () => void };

export interface ReadyTimeline {
	status: "ready";
	/** Stored messages of this scope, oldest first. */
	entries: MentorEntry[];
	/** The developer's messages the server has not stored yet. */
	pending: PendingMessage[];
	/** Queued and running turns of this scope, oldest first. */
	turns: MentorTurn[];
	earlier: EarlierMessages;
}

export type ConversationTimelineState =
	| { status: "loading" }
	| { status: "error"; onRetry: () => void }
	| ReadyTimeline;

/**
 * A thread: `missing` when the server has no such message of the developer's. A deleted message
 * still leads its thread, which keeps its replies.
 */
export type ConversationThreadState =
	| { status: "loading" }
	| { status: "error"; onRetry: () => void }
	| { status: "missing" }
	| { status: "ready"; parent: MentorEntry; timeline: ReadyTimeline };

/** Where a thread opened from a message puts focus: its composer to reply, or its heading to read. */
export type ThreadFocus = "composer" | "heading";

export interface TimelineActions {
	onVote: (entry: MentorEntry, vote: MessageVote | null) => void;
	onDelete: (entry: MentorEntry) => void;
	onStop: (turnId: string) => void;
	/** Removes a message that the server did not store from this device. */
	onDiscardSend: (requestId: string) => void;
	/**
	 * Asking Hephaestus again. Absent while the developer cannot ask, such as when their AI choice keeps
	 * Hephaestus from answering: then no message offers Reply in thread, Try again or Retry.
	 */
	ask?: {
		onRetrySend: (requestId: string) => void;
		/** Asks a question again whose turn failed. */
		onTryAgain: (question: MentorEntry) => void;
	};
}

export interface TimelineThreads {
	/** The thread open beside the conversation, if any. */
	openParentId?: string;
	/** The `id` of the thread panel, which a summary button controls. */
	panelId: string;
	onOpen: (parentEntryId: string, focus: ThreadFocus) => void;
}

export interface ConversationTimelineProps {
	/** Names the scroll region: the conversation, or one thread. */
	label: string;
	/** The workspace the links in messages and weekly digests open in. */
	workspaceSlug: string;
	state: ConversationTimelineState;
	developer: MessageAuthor;
	onCopy: (text: string) => void;
	/** The developer's writes. A read-only conversation passes none. */
	actions?: TimelineActions;
	/** Top level only: the threads on its messages. A thread passes none, since threads do not nest. */
	threads?: TimelineThreads;
	/** Above the messages: a thread's parent message. */
	lead?: ReactNode;
	/** Shown when the scope has no message yet. */
	empty?: ReactNode;
	/**
	 * Below the messages once they are read. While the first read loads or has failed, a message sent
	 * then has no page to land in, so it would not show; the skeleton or Retry stands in. A composer
	 * with a `draftKey` keeps its words, so it gives them back once the messages are read.
	 */
	composer?: ReactNode;
	/** The live stream keeps failing and is reconnecting. */
	livePaused?: boolean;
	/** A message to bring into view, from a link. */
	jumpTo?: string;
}

/** The most messages a link reads back through before it says its message is not there. */
const JUMP_READ_LIMIT = 250;

/**
 * Above the oldest loaded message: loads the older page once that message comes into view. The
 * button serves a keyboard and a failed load. Show earlier messages stands alone on the separator's
 * line, so it is 24 px tall (WCAG 2.5.8). Retry follows a sentence, where the inline exception
 * would allow less; it takes the same size, so the recovery actions in Hephaestus read as one kind.
 */
function EarlierMessagesControl({
	earlier,
	oldestId,
}: {
	earlier: EarlierMessages;
	oldestId: string | undefined;
}) {
	const { visibleMessageIds } = useMessageScrollerVisibility();
	const inView = oldestId !== undefined && visibleMessageIds[0] === oldestId;
	const onLoad = earlier.status === "available" ? earlier.onLoad : undefined;
	useEffect(() => {
		if (inView && onLoad) {
			onLoad();
		}
	}, [inView, onLoad]);

	switch (earlier.status) {
		case "none": {
			return null;
		}
		case "available": {
			return (
				<Marker variant="separator">
					<MarkerContent>
						<Button variant="outline" size="xs" onClick={earlier.onLoad}>
							Show earlier messages
						</Button>
					</MarkerContent>
				</Marker>
			);
		}
		case "loading": {
			return (
				<Marker variant="separator">
					<MarkerContent>Loading earlier messages…</MarkerContent>
				</Marker>
			);
		}
		case "error": {
			return (
				<Marker variant="separator">
					<MarkerContent>
						We could not load earlier messages.{" "}
						<Button variant="outline" size="xs" onClick={earlier.onRetry}>
							Retry
						</Button>
					</MarkerContent>
				</Marker>
			);
		}
	}
}

/**
 * Brings a linked message into view, reading older pages until it arrives, up to
 * {@link JUMP_READ_LIMIT} messages. Asked before the first page arrives, the scroller holds the
 * jump and makes it as the message mounts.
 */
function JumpTo({
	messageId,
	loaded,
	readCount,
	earlier,
}: {
	messageId: string;
	loaded: boolean;
	readCount: number;
	earlier: EarlierMessages | undefined;
}) {
	const { scrollToMessage } = useMessageScroller();
	const firstPageRead = earlier !== undefined;
	useEffect(() => {
		if (firstPageRead && !loaded) {
			return;
		}
		// A frame later. `scrollToMessage` records where its target sits before it scrolls, and when
		// rows arrive above, the scroller's `MutationObserver` callback puts that position back. Rows
		// from a query update commit, and run this effect, before that callback, which would undo the
		// jump.
		const frame = requestAnimationFrame(() => {
			scrollToMessage(messageId, { align: "start" });
		});
		return () => cancelAnimationFrame(frame);
	}, [messageId, loaded, firstPageRead, scrollToMessage]);

	const loadEarlier =
		earlier?.status === "available" && readCount < JUMP_READ_LIMIT ? earlier.onLoad : undefined;
	useEffect(() => {
		if (!loaded) {
			loadEarlier?.();
		}
	}, [loaded, loadEarlier]);

	const notFound =
		!loaded &&
		(earlier?.status === "none" || (earlier?.status === "available" && loadEarlier === undefined));
	return notFound ? (
		<Marker>
			<MarkerContent>We could not find that message. It may have been deleted.</MarkerContent>
		</Marker>
	) : null;
}

/**
 * Each message's subject, which names its controls. Messages that share one, such as two of Hephaestus's
 * in one minute, also say which of them they are.
 */
function subjectsOf(rows: TimelineRow[], developer: MessageAuthor): Map<string, string> {
	const subjects = new Map<string, string>();
	for (const row of rows) {
		if (row.kind !== "live") {
			const message =
				row.kind === "entry"
					? row.entry
					: { kind: "USER" as const, createdAt: row.message.createdAt };
			subjects.set(row.key, messageSubject(message, developer));
		}
	}
	const shared = new Map<string, number>();
	for (const subject of subjects.values()) {
		shared.set(subject, (shared.get(subject) ?? 0) + 1);
	}
	const seen = new Map<string, number>();
	for (const [key, subject] of subjects) {
		if ((shared.get(subject) ?? 0) > 1) {
			const position = (seen.get(subject) ?? 0) + 1;
			seen.set(subject, position);
			subjects.set(key, `${subject}, message ${position}`);
		}
	}
	return subjects;
}

/**
 * Says once, from its own live region, that the developer can leave a long wait. The visible line
 * also counts the seconds, which a screen reader would read out every second.
 */
function LeaveNote({ turn }: { turn: MentorTurn | undefined }) {
	const waited = useWaitedSeconds(turn);
	return (
		<p role="status" className="sr-only">
			{waited === undefined ? "" : LEAVE_NOTE}
		</p>
	);
}

/**
 * The status lines and the composer below the scroller. When this area grows, such as a composer
 * that wraps to more lines, the scroller's view shrinks: a reader at the end stays at the end, and a
 * reader further up keeps their place.
 *
 * It measures from the moment the messages are `read`. The composer arrives with them, in the commit
 * that also places the opening view; counted as growth, it would pull that view to the end.
 */
function KeepsEndInView({ read, children }: { read: boolean; children: ReactNode }) {
	const { scrollToEnd } = useMessageScroller();
	const { end: canScrollToEnd } = useMessageScrollerScrollable();
	const atEnd = useRef(!canScrollToEnd);
	const area = useRef<HTMLDivElement>(null);
	useEffect(() => {
		atEnd.current = !canScrollToEnd;
	}, [canScrollToEnd]);
	useEffect(() => {
		const element = area.current;
		if (!read || element === null) {
			return;
		}
		let { height } = element.getBoundingClientRect();
		const observer = new ResizeObserver(() => {
			const grown = element.getBoundingClientRect().height;
			if (grown > height && atEnd.current) {
				scrollToEnd({ behavior: "instant" });
			}
			height = grown;
		});
		observer.observe(element);
		return () => observer.disconnect();
	}, [read, scrollToEnd]);
	return (
		<div ref={area} className="mx-auto flex w-full max-w-3xl flex-col gap-2 px-4 pb-2">
			{children}
		</div>
	);
}

const isQuestion = (row: TimelineRow) =>
	row.kind === "pending" || (row.kind === "entry" && row.entry.kind === "USER");

interface AnchorKeys {
	/** The developer's questions that were there when the timeline opened. */
	opened: ReadonlySet<string>;
	/** Each question sent from this tab, from its unsent row on (the stored question keeps that row's key). */
	sent: ReadonlySet<string>;
}

const NO_ANCHORS: AnchorKeys = { opened: new Set(), sent: new Set() };

/**
 * The rows that scroll near the top as they appear: the questions there at opening, so the timeline
 * opens at the newest one, and each question sent from this tab. A question that arrives from Slack
 * or another tab never moves a reader. Derived during render, so a sent row is an anchor in the
 * commit that adds it, which is when the scroller reads it.
 */
function useAnchorKeys(rows: TimelineRow[], ready: boolean): AnchorKeys {
	const [anchors, setAnchors] = useState<AnchorKeys>();
	let current = anchors;
	if (current === undefined && ready) {
		current = { opened: new Set(rows.filter(isQuestion).map(({ key }) => key)), sent: new Set() };
	}
	const known = current;
	const sent = rows.filter(
		(row) =>
			row.kind === "pending" &&
			known !== undefined &&
			!known.opened.has(row.key) &&
			!known.sent.has(row.key),
	);
	if (current !== undefined && sent.length > 0) {
		current = { ...current, sent: new Set([...current.sent, ...sent.map(({ key }) => key)]) };
	}
	if (current !== anchors) {
		setAnchors(current);
	}
	return current ?? NO_ANCHORS;
}

/**
 * What deleting a message does, what it leaves (the replies in its thread, each with its own
 * Delete, and the copy in Slack of a message asked there), and that it is final.
 */
function deleteDescription(entry: MentorEntry): string {
	return [
		"Hephaestus forgets it. If it has not answered yet, it does not answer.",
		(entry.thread?.replyCount ?? 0) > 0 ? "The replies in its thread stay." : undefined,
		entry.surface === "SLACK" ? "It and Hephaestus's answer stay in Slack." : undefined,
		"You cannot undo this.",
	]
		.filter(hasText)
		.join(" ");
}

function TimelineError({ onRetry }: { onRetry: () => void }) {
	return (
		<Empty>
			<EmptyHeader>
				<EmptyMedia variant="icon">
					<MessageSquareWarningIcon />
				</EmptyMedia>
				<EmptyTitle>We could not load these messages</EmptyTitle>
				<EmptyDescription>Try again. If it keeps failing, reload the page.</EmptyDescription>
			</EmptyHeader>
			<EmptyContent>
				<Button variant="outline" onClick={onRetry}>
					Retry
				</Button>
			</EmptyContent>
		</Empty>
	);
}

/** Where the scroller put the opening view, for the rows it had then: at the end, or the last anchor at an offset from the top. */
type OpeningPlacement = { rowCount: number } & (
	| { atEnd: true }
	| { atEnd: false; anchorOffset: number }
);

const offsetIn = (viewport: HTMLElement, element: HTMLElement) =>
	element.getBoundingClientRect().top - viewport.getBoundingClientRect().top;

/**
 * The first row that renders while the view opens, or `undefined` once it has opened.
 *
 * The scroller places the opening view in its own layout effect, which runs before this one. It
 * decides from the last anchor (`start`) and the rows after it, which always render. The rows above
 * that the view shows are still 10rem placeholders then, so when they render at their real height,
 * the content moves under the view. Before the browser paints, this renders rows upward from `start`,
 * one more each time and then twice as many, until they fill one view height, and puts the view back
 * where the scroller placed it. Rows that arrive meanwhile, such as an older page, end the window: the
 * scroller places the view for them. A frame after the rows render, `contain-intrinsic-size: auto`
 * holds their real heights, and they skip rendering again when away from the view.
 *
 * A view that opens on a linked message (`jumpTo`) has no window: the scroller places that message
 * itself, also when it arrives on an older page, and keeps its place as the page settles. That opening
 * ends the window too, so the link going away later, as when the reader opens a thread, starts none.
 */
function useOpeningWindow(
	content: RefObject<HTMLDivElement | null>,
	rowCount: number,
	start: number,
	jumpTo: string | undefined,
): number | undefined {
	const [from, setFrom] = useState<number>();
	const [phase, setPhase] = useState<"opening" | "rendered" | "opened">("opening");
	const placement = useRef<OpeningPlacement>(undefined);
	const step = useRef(1);
	if (jumpTo !== undefined && phase === "opening" && rowCount > 0) {
		setPhase("opened");
	}
	useLayoutEffect(() => {
		const viewport = content.current?.closest<HTMLElement>(
			"[data-slot='message-scroller-viewport']",
		);
		const items = [
			...(content.current?.querySelectorAll<HTMLElement>(
				":scope > [data-slot='message-scroller-item']",
			) ?? []),
		];
		const anchor = items[start];
		const last = items.at(-1);
		if (
			jumpTo !== undefined ||
			phase !== "opening" ||
			rowCount === 0 ||
			!viewport ||
			!anchor ||
			!last
		) {
			return;
		}
		if (placement.current === undefined) {
			placement.current =
				viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= 1
					? { rowCount, atEnd: true }
					: { rowCount, atEnd: false, anchorOffset: offsetIn(viewport, anchor) };
		} else if (placement.current.rowCount !== rowCount) {
			setPhase("rendered");
			return;
		} else if (placement.current.atEnd) {
			viewport.scrollTop = viewport.scrollHeight - viewport.clientHeight;
		} else {
			viewport.scrollTop += offsetIn(viewport, anchor) - placement.current.anchorOffset;
		}
		const first = from ?? start;
		const shown =
			last.getBoundingClientRect().bottom - (items[first] ?? anchor).getBoundingClientRect().top;
		if (shown < viewport.clientHeight && first > 0) {
			setFrom(Math.max(0, first - step.current));
			step.current *= 2;
			return;
		}
		setPhase("rendered");
	}, [content, from, jumpTo, phase, rowCount, start]);
	useEffect(() => {
		if (phase !== "rendered") {
			return;
		}
		let frame = requestAnimationFrame(() => {
			frame = requestAnimationFrame(() => {
				setPhase("opened");
			});
		});
		return () => {
			cancelAnimationFrame(frame);
		};
	}, [phase]);
	return jumpTo !== undefined || phase === "opened" ? undefined : (from ?? start);
}

/**
 * One scope of the developer's Hephaestus conversation — the main conversation or a thread — as a log.
 * It opens at the newest question without motion. A question the developer sends scrolls near the
 * top, and the answer grows into the space the scroller keeps below it; the view never follows the
 * stream, so the reader keeps their place, and Jump to latest takes them to the end. Older messages
 * load above without moving what is on screen.
 *
 * Only rows are items of the scroller's list: it reads the first item to tell older messages from
 * new ones, and the first content to place the opening view. The loading state, the thread's parent
 * message, the earlier-messages control and the first day sit above the list, which stays mounted
 * and empty while the first page loads. Every later day closes the row before it, so a row on screen
 * keeps its content when older rows arrive above it.
 *
 * The log announces new rows, not a row whose words change: it is busy while Hephaestus writes, and the
 * status line speaks from its own live region outside the log, so the finished answer is read once.
 */
export function ConversationTimeline(props: ConversationTimelineProps) {
	const { label, state, developer, lead, empty, livePaused = false, jumpTo } = props;
	const composer = state.status === "ready" ? props.composer : undefined;
	const [deleting, setDeleting] = useState<MentorEntry | null>(null);
	const rows = state.status === "ready" ? timelineRows(state) : [];
	const subjects = subjectsOf(rows, developer);
	const last = rows.at(-1);
	const liveTurn = last?.kind === "live" ? last.turn : undefined;
	const question = rows.find(
		(row) =>
			row.kind === "entry" &&
			row.entry.kind === "USER" &&
			liveTurn !== undefined &&
			row.entry.turnId === liveTurn.id,
	);
	const questionSubject = question && subjects.get(question.key);
	const stopLabel =
		questionSubject === undefined ? "Stop answer" : `Stop answer (${questionSubject})`;
	const statusLabel = useTurnStatusLabel(liveTurn);
	const anchors = useAnchorKeys(rows, state.status === "ready");
	const isAnchor = (key: string) => anchors.opened.has(key) || anchors.sent.has(key);
	const lastAnchor = rows.map(({ key }) => isAnchor(key)).lastIndexOf(true);
	const content = useRef<HTMLDivElement>(null);
	const openingFrom = useOpeningWindow(
		content,
		rows.length,
		lastAnchor === -1 ? rows.length - 1 : lastAnchor,
		jumpTo,
	);
	const first = rows[0];
	const firstDay = first !== undefined && first.kind !== "live" ? first.day : undefined;

	return (
		<MessageScrollerProvider defaultScrollPosition="last-anchor" scrollPreviousItemPeek={64}>
			<div className="flex h-full min-h-0 flex-col">
				<MessageScroller className="flex-1">
					<MessageScrollerViewport aria-label={label}>
						{state.status === "loading" && <ConversationTimelineSkeleton />}
						<div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-6">
							{state.status === "error" && <TimelineError onRetry={state.onRetry} />}
							{lead}
							{state.status === "ready" && (
								<EarlierMessagesControl earlier={state.earlier} oldestId={first?.messageId} />
							)}
							{state.status === "ready" && rows.length === 0 && empty}
							{firstDay && <DayMarker date={firstDay} />}
							<MessageScrollerContent
								ref={content}
								aria-busy={liveTurn !== undefined}
								className="gap-4"
							>
								{rows.map((row, index) => {
									const next = rows[index + 1];
									return (
										<MessageScrollerItem
											key={row.key}
											messageId={row.messageId}
											scrollAnchor={isAnchor(row.key)}
											// The scroller measures rows in the commit that adds them, before the browser
											// renders them: the last anchor and the rows after it, for the opening view
											// (the end when that last turn fits, else its question at its peek) and for a
											// sent question's place. Under `content-visibility: auto` a row not rendered
											// yet measures its 10rem placeholder, so those rows always render. While the
											// view opens, the rows it shows render too (`useOpeningWindow`).
											className={
												(openingFrom !== undefined && index >= openingFrom) ||
												(lastAnchor !== -1 && index >= lastAnchor)
													? "[content-visibility:visible]"
													: undefined
											}
										>
											<ConversationTimelineRow
												row={row}
												nextDay={next !== undefined && next.kind !== "live" ? next.day : undefined}
												subject={subjects.get(row.key) ?? ""}
												// One Stop while Hephaestus answers: the composer's, beside Send. The status line
												// offers its own only where no composer shows, such as a thread on a
												// deleted message.
												stopLabel={composer === undefined ? stopLabel : undefined}
												props={props}
												onDelete={setDeleting}
											/>
										</MessageScrollerItem>
									);
								})}
							</MessageScrollerContent>
						</div>
					</MessageScrollerViewport>
					<MessageScrollerButton>
						<ArrowDownIcon />
						<span className="sr-only">Jump to latest</span>
					</MessageScrollerButton>
				</MessageScroller>
				<p role="status" className="sr-only">
					{statusLabel}
				</p>
				<LeaveNote turn={liveTurn} />
				<KeepsEndInView read={state.status === "ready"}>
					<div role="status">
						{livePaused && (
							<Marker>
								<MarkerContent>Live updates paused. Reconnecting…</MarkerContent>
							</Marker>
						)}
						{jumpTo !== undefined && (
							<JumpTo
								messageId={jumpTo}
								loaded={rows.some(({ messageId }) => messageId === jumpTo)}
								readCount={state.status === "ready" ? state.entries.length : 0}
								earlier={state.status === "ready" ? state.earlier : undefined}
							/>
						)}
					</div>
					{composer}
					{composer !== undefined && (
						<p className="text-center text-xs text-balance text-muted-foreground">
							Hephaestus can make mistakes. Check anything important before you rely on it.
						</p>
					)}
				</KeepsEndInView>
			</div>
			<ConfirmDialog
				subject={deleting}
				title={() => "Delete this message?"}
				description={deleteDescription}
				confirmLabel="Delete message"
				onConfirm={(entry) => props.actions?.onDelete(entry)}
				onClose={() => setDeleting(null)}
			/>
		</MessageScrollerProvider>
	);
}
