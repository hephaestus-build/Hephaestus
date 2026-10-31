import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { useEffect, useState } from "react";
import { expect, fn, screen, spyOn, waitFor, within } from "storybook/test";

import type { MentorEntry } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import type { PendingMessage } from "@/lib/conversation-entry";
import { isRecord } from "@/lib/is-record";

import { expectSettledVisible } from "@/stories/overlay";
import { expectTargetSize } from "@/stories/reflow";
import { earlierToday, minutesBefore } from "@/stories/story-clock";
import { nextFrame } from "@/test/async";

import { ConversationComposer } from "./ConversationComposer";
import {
	ConversationTimeline,
	type ConversationTimelineProps,
	type ConversationTimelineState,
} from "./ConversationTimeline";
import {
	ANSWER_WITH_FEEDBACK,
	CONVERSATION,
	DEVELOPER,
	DIGEST,
	entry,
	FAILED_FOLLOW_UP,
	FOLLOW_UP,
	FOLLOW_UPS_FULL,
	QUESTION,
	ready,
	SLACK_ANSWER,
	SLACK_QUESTION,
	turn,
	WORKSPACE_SLUG,
} from "./fixtures";
import { Greeting } from "./Greeting";

const BEFORE_FOLLOW_UP = CONVERSATION.slice(0, 4);

/**
 * A page older than the conversation fixture, on the same day as its first message, headed by the
 * message a link points to.
 */
const OLDER_PAGE: MentorEntry[] = CONVERSATION.map((message, index) => ({
	...message,
	id: `older-${message.id}`,
	seq: message.seq - 100,
	createdAt: new Date(QUESTION.createdAt.getTime() - (CONVERSATION.length - index) * 60_000),
	...(message.turnId === undefined ? {} : { turnId: `older-${message.turnId}` }),
	...(message.requestId === undefined ? {} : { requestId: `older-${message.requestId}` }),
	...(index === 0 ? { text: "The message the link points to" } : {}),
}));
const LINKED_ID = OLDER_PAGE[0]?.id ?? "";

const RUNNING = turn({
	id: FOLLOW_UP.turnId ?? "",
	status: "RUNNING",
	partial: {
		activity: "WRITING",
		stage: "Thinking…",
		text: "I looked at your latest pull request",
	},
});

const NEWEST = "Can you check my newest one too?";

const SENDING: PendingMessage = {
	requestId: "r-newest",
	text: NEWEST,
	createdAt: minutesBefore(1),
	status: "sending",
};

const STORED = entry({
	id: "e1000000-0000-4000-8000-000000000008",
	kind: "USER",
	text: NEWEST,
	requestId: SENDING.requestId,
	createdAt: SENDING.createdAt,
});

/** The row of the message that shows `text`, as the scroller lays it out. */
function rowOf(text: HTMLElement): HTMLElement {
	const row = text.closest<HTMLElement>("[data-slot=message-scroller-item]");
	if (row === null) {
		throw new Error("Expected the message inside a scroller row");
	}
	return row;
}

/**
 * Reads the conversation as the app does: a skeleton first, the newest page a frame later, then
 * older ones on demand.
 */
function ColdLoad(props: ConversationTimelineProps) {
	const [phase, setPhase] = useState<"loading" | "newest" | "all">("loading");
	useEffect(() => {
		const arrive = requestAnimationFrame(() => setPhase("newest"));
		return () => cancelAnimationFrame(arrive);
	}, []);
	let state: ConversationTimelineState = { status: "loading" };
	if (phase === "newest") {
		state = ready({ earlier: { status: "available", onLoad: () => setPhase("all") } });
	} else if (phase === "all") {
		state = ready({ entries: [...OLDER_PAGE, ...CONVERSATION] });
	}
	return <ConversationTimeline {...props} state={state} />;
}

/** One state, then the next once the story presses Next, as one cache update brings it. */
function Transition({
	before,
	after,
	...props
}: ConversationTimelineProps & {
	before: ConversationTimelineState;
	after: ConversationTimelineState;
}) {
	const [next, setNext] = useState(false);
	return (
		<div className="flex h-full flex-col">
			<Button variant="outline" onClick={() => setNext(true)}>
				Next
			</Button>
			<div className="min-h-0 flex-1">
				<ConversationTimeline {...props} state={next ? after : before} />
			</div>
		</div>
	);
}

/** The newest page, and the older one once the timeline asks for it. */
function Paged(props: ConversationTimelineProps) {
	const [all, setAll] = useState(false);
	const state = all
		? ready({ entries: [...OLDER_PAGE, ...CONVERSATION] })
		: ready({ earlier: { status: "available", onLoad: () => setAll(true) } });
	return <ConversationTimeline {...props} state={state} />;
}

const stopRunning = fn();

/** The composer as the conversation passes it while Hephaestus answers: Stop sits beside Send. */
const BUSY_COMPOSER = (
	<ConversationComposer
		label="Message Hephaestus"
		placeholder="Message Hephaestus"
		onSend={fn()}
		stop={{ label: "Stop answer", onStop: stopRunning }}
	/>
);

const meta = {
	component: ConversationTimeline,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		label: "Your conversation",
		workspaceSlug: WORKSPACE_SLUG,
		state: ready(),
		developer: DEVELOPER,
		onCopy: fn(),
		actions: {
			onVote: fn(),
			onDelete: fn(),
			onStop: fn(),
			onDiscardSend: fn(),
			ask: { onRetrySend: fn(), onTryAgain: fn() },
		},
		threads: { panelId: "thread-panel", onOpen: fn() },
		empty: <Greeting />,
		composer: (
			<ConversationComposer
				label="Message Hephaestus"
				placeholder="Message Hephaestus"
				onSend={fn()}
			/>
		),
	},
	decorators: [
		(Story) => (
			<div className="h-dvh">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ConversationTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Every row left-aligned, a day separator for each local day, Slack-written messages marked, and a
 * thread summary on the message that has replies. Opening it puts focus on the thread's heading.
 */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("region", { name: "Your conversation" })).toBeVisible();
		await expect(canvas.getByText("Yesterday")).toBeVisible();
		await expect(canvas.getByText("Today")).toBeVisible();
		await expect(canvas.getAllByText("Slack")).toHaveLength(2);
		const summary = canvas.getByRole("button", { name: /^3 replies/u });
		// The visible words first, then the message, so two equal summaries keep apart.
		await expect(summary).toHaveAccessibleName(
			/^3 replies · Last reply about 1 hour ago \(Ada Lovelace, .+\)$/u,
		);
		await expectTargetSize(summary);
		await userEvent.click(summary);
		await expect(args.threads?.onOpen).toHaveBeenCalledWith(SLACK_QUESTION.id, "heading");
	},
};

/**
 * Reply in thread opens the thread with its composer in focus. Each message's controls name the
 * message, so a screen reader's list of buttons tells them apart.
 */
export const ReplyInThread: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const replies = canvas.getAllByRole("button", { name: /^Reply in thread \(/u });
		const names = replies.map((button) => button.getAttribute("aria-label"));
		await expect(new Set(names).size).toBe(names.length);
		const [first] = replies;
		if (!first) {
			throw new Error("Expected a Reply in thread on the first message");
		}
		await userEvent.click(first);
		await expect(args.threads?.onOpen).toHaveBeenCalledWith(CONVERSATION[0]?.id, "composer");
	},
};

/** Read, with no message yet: the greeting, and the composer to start with. */
export const Empty: Story = {
	args: { state: ready({ entries: [] }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Message Hephaestus" })).toBeVisible();
	},
};

/**
 * The messages are still on their way. The story passes a composer, and none shows: a message sent
 * now would have no page to land in. The composer comes with the messages.
 */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		// The skeleton waits a second, so a read from the cache shows none.
		await expect(
			await canvas.findByText("Loading the conversation…", undefined, { timeout: 3000 }),
		).toBeVisible();
		await expect(canvas.queryByRole("textbox", { name: "Message Hephaestus" })).toBeNull();
		await expect(canvas.queryByText(/^Hephaestus can make mistakes/u)).toBeNull();
	},
};

const retryLoad = fn();

/**
 * The messages could not be read. The story passes a composer, and none shows: a message sent now
 * would not appear until Retry reads the messages. The words say nothing of which scope failed, as
 * the conversation and a thread both show them.
 */
export const LoadError: Story = {
	args: { state: { status: "error", onRetry: retryLoad } },
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText("We could not load these messages")).toBeVisible();
		await expect(canvas.queryByRole("textbox", { name: "Message Hephaestus" })).toBeNull();
		await expect(canvas.queryByText(/^Hephaestus can make mistakes/u)).toBeNull();
		await userEvent.click(await canvas.findByRole("button", { name: "Retry" }));
		await expect(retryLoad).toHaveBeenCalledOnce();
	},
};

/**
 * Before Hephaestus shows words, its place in the conversation says what it does. One Stop ends the
 * answer: the composer's, beside Send, where the developer just asked. The status line offers none
 * of its own, so two controls never do the same thing.
 */
export const Thinking: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
			turns: [turn({ id: FOLLOW_UP.turnId ?? "", status: "RUNNING" })],
		}),
		composer: BUSY_COMPOSER,
	},
	play: async ({ canvas, userEvent }) => {
		const log = canvas.getByRole("log");
		await expect(log).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByText("Thinking…", { selector: "p" })).toHaveAttribute(
			"role",
			"status",
		);
		await expect(within(log).queryByRole("button", { name: /^Stop/u })).toBeNull();
		await expect(canvas.getAllByRole("button", { name: /^Stop/u })).toHaveLength(1);
		await userEvent.click(canvas.getByRole("button", { name: "Stop answer" }));
		await expect(stopRunning).toHaveBeenCalledOnce();
	},
};

/**
 * Where no composer shows, such as a thread on a deleted message, the status line offers Stop, and
 * it names the question it ends.
 */
export const ThinkingWithoutComposer: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
			turns: [turn({ id: FOLLOW_UP.turnId ?? "", status: "RUNNING" })],
		}),
		composer: undefined,
	},
	play: async ({ args, canvas, userEvent }) => {
		const stop = within(canvas.getByRole("log")).getByRole("button", {
			name: /^Stop answer \(Ada Lovelace, /u,
		});
		await expectTargetSize(stop);
		await userEvent.click(stop);
		await expect(args.actions?.onStop).toHaveBeenCalledWith(FOLLOW_UP.turnId);
	},
};

/** A follow-up written while Hephaestus answers waits under its question. Its Stop is not the running answer's. */
export const QueuedFollowUp: Story = {
	args: {
		composer: BUSY_COMPOSER,
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
			turns: [
				turn({
					id: "t-running",
					status: "RUNNING",
					partial: { activity: "THINKING", stage: "Thinking…", text: "" },
				}),
				turn({ id: FOLLOW_UP.turnId ?? "", status: "QUEUED" }),
			],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Queued. This is answered next.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^Stop queued message \(/u }));
		await expect(args.actions?.onStop).toHaveBeenCalledWith(FOLLOW_UP.turnId);
		await userEvent.click(canvas.getByRole("button", { name: "Stop answer" }));
		await expect(stopRunning).toHaveBeenCalledOnce();
		await expect(args.actions?.onStop).toHaveBeenCalledOnce();
	},
};

const SECOND_FOLLOW_UP = entry({
	id: "e1000000-0000-4000-8000-000000000011",
	kind: "USER",
	text: "And the one before it?",
	turnId: "t1000000-0000-4000-8000-000000000011",
	requestId: "r1000000-0000-4000-8000-000000000011",
	createdAt: earlierToday(3),
});

const THIRD_FOLLOW_UP = entry({
	id: "e1000000-0000-4000-8000-000000000012",
	kind: "USER",
	text: "Which one needs the most work?",
	turnId: "t1000000-0000-4000-8000-000000000012",
	requestId: "r1000000-0000-4000-8000-000000000012",
	createdAt: earlierToday(2),
});

/** Only the first follow-up in the queue says Hephaestus answers it next. Each Stop names its own message. */
export const FollowUpsQueued: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP, SECOND_FOLLOW_UP, THIRD_FOLLOW_UP],
			turns: [
				turn({ id: FOLLOW_UP.turnId ?? "", status: "RUNNING" }),
				turn({ id: SECOND_FOLLOW_UP.turnId ?? "", status: "QUEUED" }),
				turn({ id: THIRD_FOLLOW_UP.turnId ?? "", status: "QUEUED" }),
			],
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Queued. This is answered next.")).toHaveLength(1);
		await expect(canvas.getAllByText("Queued.")).toHaveLength(1);
		const stops = canvas.getAllByRole("button", { name: /^Stop queued message \(/u });
		const names = stops.map((button) => button.getAttribute("aria-label"));
		await expect(new Set(names).size).toBe(2);
	},
};

const WAITING_FOR_CAPACITY =
	"Hephaestus is answering other questions. Yours starts when one finishes.";

/**
 * Every Hephaestus slot answers another question, so the developer's question waits for a free Hephaestus. Its
 * place in the conversation says so, for sighted readers and screen readers alike.
 */
export const WaitingForCapacity: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
			turns: [turn({ id: FOLLOW_UP.turnId ?? "", status: "QUEUED", waitingFor: "FREE_HEPH" })],
		}),
	},
	play: async ({ canvas }) => {
		const log = canvas.getByRole("log");
		await expect(
			await within(log).findByText(WAITING_FOR_CAPACITY, {}, { timeout: 3000 }),
		).toBeVisible();
		// The live region outside the log runs its own one-second delay, so it can change a moment later.
		await expect(
			await canvas.findByText(WAITING_FOR_CAPACITY, { selector: "p" }, { timeout: 3000 }),
		).toHaveAttribute("role", "status");
		await expect(canvas.queryByText(/^Queued\./u)).toBeNull();
	},
};

/** How far the view is from the end of the conversation. */
const fromEnd = (viewport: HTMLElement) =>
	viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;

/**
 * Waits until the view holds still: its scroll position and its content height are the same over two
 * frames. The scroller settles over several frames: each resize makes it reanchor on the next one.
 */
async function viewHeldStill(viewport: HTMLElement) {
	await waitFor(async () => {
		const before = [viewport.scrollTop, viewport.scrollHeight];
		await nextFrame();
		await nextFrame();
		await expect([viewport.scrollTop, viewport.scrollHeight]).toStrictEqual(before);
	});
}

/**
 * Where the conversation opens: at the end when its last turn fits in the view, or with that turn's
 * question at its peek, so that a long answer is read from its start.
 */
type Opening = { at: "end" } | { at: "peek"; question: HTMLElement };

/**
 * Checks the opening view, presses Next, and checks that `row` took the change in place while the view
 * stayed where the reader was: the view never follows the stream, so a row that grows does not move
 * what the reader sees. The view is read only once it holds still, before the change and after it.
 */
async function expectViewKept(
	viewport: HTMLElement,
	row: HTMLElement,
	opening: Opening,
	pressNext: () => Promise<void>,
	changed: () => Promise<void>,
) {
	await waitFor(async () => {
		await expect(viewport.scrollTop).toBeGreaterThan(0);
	});
	await viewHeldStill(viewport);
	if (opening.at === "end") {
		await expect(fromEnd(viewport), "distance from the end at opening").toBeLessThanOrEqual(1);
	} else {
		const questionOffset =
			opening.question.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
		await expect(Math.round(questionOffset)).toBe(PEEK);
	}
	const rowOffset = row.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
	await pressNext();
	await waitFor(changed);
	await viewHeldStill(viewport);
	await expect(row).toBeVisible();
	const nextOffset = row.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
	await expect(Math.abs(nextOffset - rowOffset)).toBeLessThanOrEqual(1);
}

const SHORT_VIEW: Decorator[] = [
	(Story) => (
		<div className="h-[28rem]">
			<Story />
		</div>
	),
];

/** A sent message becomes the stored one in place, and the view stays. */
export const SentMessageStored: Story = {
	render: (args) => (
		<Transition
			{...args}
			before={ready({ pending: [SENDING] })}
			after={ready({ entries: [...CONVERSATION, STORED] })}
		/>
	),
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const row = rowOf(await canvas.findByText(NEWEST));
		await expectViewKept(
			canvas.getByRole("region", { name: "Your conversation" }),
			row,
			{ at: "end" },
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => expect(row).toHaveAttribute("data-message-id", STORED.id),
		);
	},
};

/**
 * The last question and its short answer fit in the view together, so the conversation opens at the
 * end. As 10rem placeholders, the two rows would not fit, and the view would open at the question.
 * The timeline renders its rows while the view opens, so the opening rests on their real height.
 */
export const OpensAtTheEndWhenTheLastTurnFits: Story = {
	args: {
		state: ready({
			entries: [
				...BEFORE_FOLLOW_UP,
				FOLLOW_UP,
				entry({
					id: "e1000000-0000-4000-8000-000000000010",
					kind: "HEPH",
					text: "Yes, the cache keeps its entries for a day.",
					turnId: FOLLOW_UP.turnId,
					createdAt: FOLLOW_UP.createdAt,
				}),
			],
		}),
	},
	decorators: SHORT_VIEW,
	play: async ({ canvas }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		await waitFor(async () => {
			await expect(viewport.scrollTop).toBeGreaterThan(0);
		});
		await viewHeldStill(viewport);
		await expect(fromEnd(viewport)).toBeLessThanOrEqual(1);
		const question = rowOf(canvas.getByText(FOLLOW_UP.text));
		await expect(question).toBeVisible();
		// The rows above the question render only while the view opens, then skip rendering again
		// when away from the view, and keep their real height.
		const rows = [...viewport.querySelectorAll<HTMLElement>("[data-slot='message-scroller-item']")];
		const above = rows.slice(0, rows.indexOf(question));
		await waitFor(async () => {
			await expect(above.map((row) => getComputedStyle(row).contentVisibility)).toStrictEqual(
				above.map(() => "auto"),
			);
		});
		await viewHeldStill(viewport);
		await expect(fromEnd(viewport)).toBeLessThanOrEqual(1);
	},
};

/** Many short replies, then a last question and its short answer. */
const REPLIES = Array.from({ length: 60 }, (_, index) =>
	entry({
		id: `e2000000-0000-4000-8000-${String(index).padStart(12, "0")}`,
		kind: "HEPH",
		text: `Reply ${index + 1}: the cache keeps its entries for a day.`,
	}),
);
const AFTER_REPLIES = [
	...REPLIES,
	entry({
		id: "e2000000-0000-4000-8000-000000000100",
		kind: "USER",
		text: "Does that hold for the staging cache too?",
		turnId: "t2000000-0000-4000-8000-000000000100",
	}),
	entry({
		id: "e2000000-0000-4000-8000-000000000101",
		kind: "HEPH",
		text: "Yes, it does.",
		turnId: "t2000000-0000-4000-8000-000000000100",
	}),
];
const LINKED_REPLY = REPLIES[20];

/** Opens on a linked message, then drops the link once the story presses Next, as opening a thread does. */
function LinkDropped({ jumpTo, ...props }: ConversationTimelineProps) {
	const [linked, setLinked] = useState(true);
	return (
		<div className="flex h-full flex-col">
			<Button variant="outline" onClick={() => setLinked(false)}>
				Next
			</Button>
			<div className="min-h-0 flex-1">
				<ConversationTimeline {...props} jumpTo={linked ? jumpTo : undefined} />
			</div>
		</div>
	);
}

/**
 * The view opened on a linked message stays where the reader is when the link goes away, as when they
 * open a thread beside the conversation.
 */
export const LinkDroppedKeepsPlace: Story = {
	args: { jumpTo: LINKED_REPLY?.id, state: ready({ entries: AFTER_REPLIES }) },
	render: (args) => <LinkDropped {...args} />,
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		const row = rowOf(await canvas.findByText(LINKED_REPLY?.text ?? ""));
		const offset = () => row.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
		await waitFor(async () => {
			await expect(Math.abs(offset())).toBeLessThan(2);
		});
		await viewHeldStill(viewport);
		const before = offset();
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await nextFrame();
		await viewHeldStill(viewport);
		await expect(Math.abs(offset() - before)).toBeLessThanOrEqual(1);
	},
};

/**
 * A long answer is read from its start: its question opens at its peek. When the answer is stored and
 * its row grows below the fold, what the reader sees stays where it was.
 */
export const LongAnswerStoredWhileReadingItsStart: Story = {
	render: (args) => (
		<Transition
			{...args}
			before={ready({
				entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
				turns: [
					turn({
						id: FOLLOW_UP.turnId ?? "",
						status: "RUNNING",
						partial: { activity: "WRITING", stage: "Thinking…", text: wordsOf(180) },
					}),
				],
			})}
			after={ready({
				entries: [
					...BEFORE_FOLLOW_UP,
					FOLLOW_UP,
					entry({
						id: "e1000000-0000-4000-8000-000000000009",
						kind: "HEPH",
						text: wordsOf(220),
						turnId: FOLLOW_UP.turnId,
						createdAt: FOLLOW_UP.createdAt,
					}),
				],
			})}
		/>
	),
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const question = rowOf(await canvas.findByText(FOLLOW_UP.text));
		await expectViewKept(
			canvas.getByRole("region", { name: "Your conversation" }),
			question,
			{ at: "peek", question },
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => {
				await canvas.findByText(wordsOf(220));
			},
		);
	},
};

/**
 * A question the developer sends lands near the top of the view, and the scroller keeps space below
 * it for the answer to grow into.
 */
export const AnchoredSend: Story = {
	render: (args) => <Transition {...args} before={ready()} after={ready({ pending: [SENDING] })} />,
	decorators: SHORT_VIEW,
	play: async ({ canvas, canvasElement, userEvent }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		const row = rowOf(await canvas.findByText(NEWEST));
		await waitFor(async () => {
			const offset = row.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
			await expect(offset).toBeLessThanOrEqual(viewport.clientHeight / 3);
		});
		await expect(canvasElement.querySelector("[data-message-scroller-spacer]")).not.toBeNull();
	},
};

/** The steps of one send as the app's cache takes them: unsent, stored and queued, three partial answers, answered. */
const SEND_STEPS = 6;

/** Hephaestus's words, `count` of them. */
const wordsOf = (count: number) =>
	Array.from({ length: count }, (_, index) => `word${index}`).join(" ");

const sentQuestion = (send: number) => `Question number ${send}`;

/** One send of {@link SendsInARow} at `phase`, after every earlier send was answered. */
function sendsUpTo(send: number, phase: number): ConversationTimelineState {
	const requestId = `r-send-${send}`;
	const turnId = `t-send-${send}`;
	const createdAt = minutesBefore(1);
	const answered = Array.from({ length: send }, (_, earlier) => [
		entry({
			id: `q-send-${earlier}`,
			kind: "USER",
			text: sentQuestion(earlier),
			requestId: `r-send-${earlier}`,
			turnId: `t-send-${earlier}`,
			createdAt,
		}),
		entry({
			id: `a-send-${earlier}`,
			kind: "HEPH",
			text: wordsOf(150),
			turnId: `t-send-${earlier}`,
			createdAt,
		}),
	]).flat();
	if (phase === 0) {
		return ready({
			entries: [...CONVERSATION, ...answered],
			pending: [{ requestId, text: sentQuestion(send), createdAt, status: "sending" }],
		});
	}
	const question = entry({
		id: `q-send-${send}`,
		kind: "USER",
		text: sentQuestion(send),
		requestId,
		turnId,
		createdAt,
	});
	const queued = turn({ id: turnId, requestId, status: "QUEUED" });
	if (phase === 1) {
		return ready({ entries: [...CONVERSATION, ...answered, question], turns: [queued] });
	}
	if (phase < SEND_STEPS - 1) {
		return ready({
			entries: [...CONVERSATION, ...answered, question],
			turns: [
				{
					...queued,
					status: "RUNNING",
					attempt: 1,
					partial: { activity: "WRITING", stage: "Thinking…", text: wordsOf(phase * 40) },
				},
			],
		});
	}
	const answer = entry({
		id: `a-send-${send}`,
		kind: "HEPH",
		text: wordsOf(150),
		turnId,
		createdAt,
	});
	return ready({ entries: [...CONVERSATION, ...answered, question, answer] });
}

/** Sends one question after another, each through every step the app's cache takes, at each press of Next. */
function SendsInARow(props: ConversationTimelineProps) {
	const [step, setStep] = useState(-1);
	const state = step < 0 ? ready() : sendsUpTo(Math.floor(step / SEND_STEPS), step % SEND_STEPS);
	return (
		<div className="flex h-full flex-col">
			<Button variant="outline" onClick={() => setStep((current) => current + 1)}>
				Next
			</Button>
			<div className="min-h-0 flex-1">
				<ConversationTimeline {...props} state={state} />
			</div>
		</div>
	);
}

/** The scroller's `scrollPreviousItemPeek`: how much of the row above a sent question stays in view. */
const PEEK = 64;

/**
 * Every question the developer sends lands at the same place near the top, in the first frame that
 * shows it, and stays there while it is stored and the answer streams in below it. Twenty sends in
 * a row, so a send that lands lower only some of the time fails here.
 */
export const SentQuestionAnchorsEveryTime: Story = {
	render: (args) => <SendsInARow {...args} />,
	decorators: SHORT_VIEW,
	play: async ({ canvas }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		await canvas.findByText("And my latest one?");
		// A plain press: 120 steps of simulated pointer events would outlast the test's time limit.
		const next = canvas.getByRole("button", { name: "Next" });
		for (let send = 0; send < 20; send += 1) {
			for (let phase = 0; phase < SEND_STEPS; phase += 1) {
				next.click();
				for (let frame = 0; frame < 2; frame += 1) {
					await nextFrame();
					const row = rowOf(canvas.getByText(sentQuestion(send)));
					const offset = row.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
					await expect(
						Math.abs(offset - PEEK),
						JSON.stringify({ send, phase, frame, offset }),
					).toBeLessThanOrEqual(1);
				}
			}
		}
	},
};

/** Hephaestus's answer as it streams: three more words at each press of Next. */
function Streams(props: ConversationTimelineProps) {
	const [words, setWords] = useState(1);
	const text = Array.from({ length: words * 3 }, (_, index) => `word${index}`).join(" ");
	return (
		<div className="flex h-full flex-col">
			<Button variant="outline" onClick={() => setWords((count) => count + 1)}>
				Next
			</Button>
			<div className="min-h-0 flex-1">
				<ConversationTimeline
					{...props}
					state={ready({
						entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
						turns: [{ ...RUNNING, partial: { activity: "WRITING", stage: "Thinking…", text } }],
					})}
				/>
			</div>
		</div>
	);
}

/**
 * The view never follows the stream: a reader further up keeps their place while Hephaestus writes, and
 * Jump to latest shows until they are at the end again.
 */
export const StreamingKeepsPlace: Story = {
	render: (args) => <Streams {...args} />,
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		const jump = canvas.getByRole("button", { name: "Jump to latest" });
		await canvas.findByText("Writing…", { selector: "span" });
		// The reader scrolls up, as a wheel does, which the scroller reads as leaving the end.
		viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: -2000, bubbles: true }));
		viewport.scrollTop = 0;
		await waitFor(async () => {
			await expect(jump).toHaveAttribute("data-active", "true");
		});
		for (let update = 0; update < 3; update += 1) {
			await userEvent.click(canvas.getByRole("button", { name: "Next" }));
			await nextFrame();
			await nextFrame();
			await expect(viewport.scrollTop).toBe(0);
		}
		await userEvent.click(jump);
		await waitFor(async () => {
			await expect(jump).toHaveAttribute("data-active", "false");
		});
	},
};

/** The `scrollTo` calls of the story that runs, for a story that checks how the view moved. */
let scrollTo: { mock: { calls: unknown[][] }; mockRestore: () => void } | undefined;

const FROM_SLACK = entry({
	id: "e1000000-0000-4000-8000-000000000021",
	kind: "USER",
	surface: "SLACK",
	text: "A question asked in Slack meanwhile",
	createdAt: earlierToday(1),
});

/**
 * A question that arrives from Slack or another tab never moves a reader who scrolled up; only the
 * developer's own send from here scrolls near the top. Jump to latest shows the way down.
 */
export const RemoteQuestionKeepsPlace: Story = {
	render: (args) => (
		<Transition
			{...args}
			before={ready()}
			after={ready({ entries: [...CONVERSATION, FROM_SLACK] })}
		/>
	),
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		await canvas.findByText("And my latest one?");
		viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: -2000, bubbles: true }));
		viewport.scrollTop = 0;
		await nextFrame();
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await canvas.findByText("A question asked in Slack meanwhile");
		await nextFrame();
		await nextFrame();
		await expect(viewport.scrollTop).toBe(0);
		await waitFor(async () => {
			await expect(canvas.getByRole("button", { name: "Jump to latest" })).toHaveAttribute(
				"data-active",
				"true",
			);
		});
	},
};

/** The conversation opens positioned, at its newest question, without a smooth scroll. */
export const OpensWithoutMotion: Story = {
	beforeEach: () => {
		scrollTo = spyOn(HTMLElement.prototype, "scrollTo");
		return () => scrollTo?.mockRestore();
	},
	decorators: SHORT_VIEW,
	play: async ({ canvas }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		await canvas.findByText("And my latest one?");
		await nextFrame();
		await nextFrame();
		const smooth = (scrollTo?.mock.calls ?? []).filter(
			([options]) => isRecord(options) && options.behavior === "smooth",
		);
		await expect(smooth).toStrictEqual([]);
		await expect(viewport).toBeVisible();
	},
};

/**
 * A composer that grows, for a longer question, keeps the end in view for a reader at the end, and
 * leaves a reader further up where they were.
 */
export const ComposerGrowth: Story = {
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		const composer = canvas.getByRole("textbox", { name: "Message Hephaestus" });
		const atEnd = () => viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
		// The scroller keeps the view hidden until it has placed the opening view; jump only after that.
		await waitFor(async () => {
			await expect(viewport).toBeVisible();
		});
		await userEvent.click(canvas.getByRole("button", { name: "Jump to latest" }));
		await waitFor(async () => {
			await expect(atEnd(), "distance from the end after Jump to latest").toBeLessThanOrEqual(1);
		});
		await userEvent.type(composer, "One{Shift>}{Enter}{/Shift}Two{Shift>}{Enter}{/Shift}Three");
		await waitFor(async () => {
			await expect(atEnd(), "distance from the end after the composer grew").toBeLessThanOrEqual(1);
		});

		viewport.scrollTop = 0;
		await nextFrame();
		await userEvent.type(composer, "{Shift>}{Enter}{/Shift}Four{Shift>}{Enter}{/Shift}Five");
		await nextFrame();
		await nextFrame();
		await expect(viewport.scrollTop).toBe(0);
	},
};

/** A follow-up sent while Hephaestus answers is stored and queued in place, and the view stays. */
export const FollowUpQueued: Story = {
	render: (args) => (
		<Transition
			{...args}
			before={ready({
				entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
				turns: [RUNNING],
				pending: [SENDING],
			})}
			after={ready({
				entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP, { ...STORED, turnId: "t-next" }],
				turns: [RUNNING, turn({ id: "t-next", status: "QUEUED", requestId: SENDING.requestId })],
			})}
		/>
	),
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const row = rowOf(await canvas.findByText(NEWEST));
		await expectViewKept(
			canvas.getByRole("region", { name: "Your conversation" }),
			row,
			{ at: "end" },
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => expect(row).toHaveTextContent("Queued. This is answered next."),
		);
	},
};

/** Hephaestus's answer in progress becomes the stored answer in place as the turn ends, and the view stays. */
export const AnswerStored: Story = {
	render: (args) => (
		<Transition
			{...args}
			before={ready({ entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP], turns: [RUNNING] })}
			after={ready()}
		/>
	),
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		// The live answer's words are separate elements while they fade in; its status line names the row.
		const row = rowOf(await canvas.findByText("Writing…", { selector: "span" }));
		await expectViewKept(
			canvas.getByRole("region", { name: "Your conversation" }),
			row,
			{ at: "end" },
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => expect(row).toHaveTextContent("What made you pick the time-based cache?"),
		);
	},
};

/** Hephaestus could not answer. The question stays, with the reason and Try again. */
export const Failed: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FAILED_FOLLOW_UP],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Hephaestus took too long to answer.")).toBeVisible();
		const tryAgain = canvas.getByRole("button", { name: /^Try again \(/u });
		await expectTargetSize(tryAgain);
		await userEvent.click(tryAgain);
		await expect(args.actions?.ask?.onTryAgain).toHaveBeenCalledWith(FAILED_FOLLOW_UP);
	},
};

/**
 * The developer cannot ask Hephaestus now, such as when their AI choice keeps Hephaestus from answering. They
 * still read, vote and delete, but no message offers Reply in thread or Try again, which would
 * only be refused, and the composer is gone.
 */
export const CannotAsk: Story = {
	args: {
		state: ready({ entries: [...BEFORE_FOLLOW_UP, FAILED_FOLLOW_UP] }),
		actions: { onVote: fn(), onDelete: fn(), onStop: fn(), onDiscardSend: fn() },
		composer: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus took too long to answer.")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /^Try again \(/u })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Reply in thread \(/u })).toBeNull();
		await expect(
			canvas.getAllByRole("button", { name: /^Delete message \(/u }).length,
		).toBeGreaterThan(0);
	},
};

/**
 * Hephaestus failed after it wrote part of an answer, such as when the AI model stopped responding. The
 * reason sits under what Hephaestus wrote, and Try again asks the question again.
 */
export const FailedAfterPartOfAnAnswer: Story = {
	args: {
		state: ready({
			entries: [
				...BEFORE_FOLLOW_UP,
				{ ...FAILED_FOLLOW_UP, turnError: "The AI model stopped responding. Try again." },
				{ ...ANSWER_WITH_FEEDBACK, links: [] },
			],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("The AI model stopped responding. Try again.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^Try again \(/u }));
		await expect(args.actions?.ask?.onTryAgain).toHaveBeenCalledWith(
			expect.objectContaining({ id: FAILED_FOLLOW_UP.id }),
		);
	},
};

/**
 * Hephaestus answered a question asked in Slack, but the answer did not fully reach Slack. The full answer
 * is here, and the line under it says so, so the developer does not wait for the rest in Slack.
 */
export const NotFullyDeliveredToSlack: Story = {
	args: {
		state: ready({
			entries: [
				...CONVERSATION.slice(0, 2),
				{ ...SLACK_QUESTION, slackDelivery: "FAILED" },
				// Slack showed this feedback in the stream before it refused the stream's last step.
				{
					...SLACK_ANSWER,
					links: ANSWER_WITH_FEEDBACK.links.filter(({ kind }) => kind === "FEEDBACK"),
				},
			],
		}),
	},
	play: async ({ canvas }) => {
		const line = canvas.getByText("Not fully delivered to Slack. The full answer is here.");
		await expect(line).toBeVisible();
		await expect(rowOf(line)).toHaveTextContent(/Here is one way to shape it/u);
		// It never claims the answer is only here: part of it, the feedback too, may be in Slack.
		await expect(canvas.queryByText(/only here/u)).toBeNull();
	},
};

export const NotSent: Story = {
	args: {
		state: ready({
			pending: [
				{
					requestId: "r-unsent",
					text: "Can you check my newest one?",
					createdAt: minutesBefore(1),
					status: "failed",
					reason: FOLLOW_UPS_FULL,
				},
			],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		const pending = within(rowOf(await canvas.findByText("Can you check my newest one?")));
		const retry = pending.getByRole("button", { name: /^Retry \(/u });
		await expectTargetSize(retry);
		await userEvent.click(retry);
		await expect(args.actions?.ask?.onRetrySend).toHaveBeenCalledWith("r-unsent");
		// The composer was cleared on send, so the words stay reachable here: Copy them, or delete the
		// message from this device instead of retrying.
		await userEvent.click(pending.getByRole("button", { name: /^Copy message \(/u }));
		await expect(args.onCopy).toHaveBeenCalledWith("Can you check my newest one?");
		await userEvent.click(pending.getByRole("button", { name: /^Delete message \(/u }));
		await expect(args.actions?.onDiscardSend).toHaveBeenCalledWith("r-unsent");
	},
};

/**
 * Scrolled to its top, the conversation reads the older page above what is on screen, and the
 * words the reader looked at keep their place, also when the older page ends on the same day.
 */
export const LoadsEarlier: Story = {
	render: (args) => <Paged {...args} />,
	decorators: SHORT_VIEW,
	play: async ({ canvas }) => {
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		const oldest = await canvas.findByText(QUESTION.text);
		await waitFor(async () => {
			await expect(viewport.scrollTop).toBeGreaterThan(0);
		});
		// The reader scrolls up, as a wheel does, which releases the opening anchor.
		viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: -2000, bubbles: true }));
		viewport.scrollTop = 0;
		const { top } = oldest.getBoundingClientRect();
		await canvas.findByText("The message the link points to");
		await waitFor(async () => {
			await expect(Math.abs(oldest.getBoundingClientRect().top - top)).toBeLessThan(2);
		});
		await expect(viewport.scrollTop).toBeGreaterThan(0);
	},
};

const loadOlder = fn();

/** Older messages wait above the oldest loaded one. The control sits on its own line, 24 px tall. */
export const EarlierAvailable: Story = {
	args: { state: ready({ earlier: { status: "available", onLoad: loadOlder } }) },
	play: async ({ canvas, userEvent }) => {
		const show = await canvas.findByRole("button", { name: "Show earlier messages" });
		await expectTargetSize(show);
		await userEvent.click(show);
		await expect(loadOlder).toHaveBeenCalled();
	},
};

/** Older messages failed to load; the rest of the conversation stays usable. */
const retryEarlier = fn();

export const EarlierError: Story = {
	args: { state: ready({ earlier: { status: "error", onRetry: retryEarlier } }) },
	play: async ({ canvas, userEvent }) => {
		const retry = await canvas.findByRole("button", { name: "Retry" });
		await expectTargetSize(retry);
		await userEvent.click(retry);
		await expect(retryEarlier).toHaveBeenCalledOnce();
	},
};

/**
 * A link from Slack or a weekly digest lands on its message on a cold load: after the skeleton,
 * and when the message sits on an older page than the newest one. It stays there as the pages
 * settle.
 */
export const LinkedMessage: Story = {
	args: { jumpTo: LINKED_ID },
	render: (args) => <ColdLoad {...args} />,
	play: async ({ canvas }) => {
		const linked = rowOf(await canvas.findByText("The message the link points to"));
		const viewport = canvas.getByRole("region", { name: "Your conversation" });
		const offset = () => linked.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
		await waitFor(async () => {
			await expect(Math.abs(offset())).toBeLessThan(2);
		});
		// The older page arrives above it and the scroller restores its place over the next frames.
		const offsets: number[] = [];
		for (let frame = 0; frame < 10; frame += 1) {
			await nextFrame();
			offsets.push(Math.round(offset()));
		}
		await expect(offsets.filter((each) => Math.abs(each) >= 2)).toStrictEqual([]);
	},
};

/**
 * A link to a message that is not in the conversation, such as a deleted one, stops reading older
 * pages after a bounded number of messages and says so.
 */
const loadEarlier = fn();

export const LinkedMessageNotFound: Story = {
	args: {
		jumpTo: "e9999999-0000-4000-8000-000000000000",
		state: ready({
			entries: Array.from({ length: 250 }, (_, index) =>
				entry({
					id: `many-${index}`,
					kind: index % 2 === 0 ? "USER" : "HEPH",
					text: `Message ${index}`,
				}),
			),
			earlier: { status: "available", onLoad: loadEarlier },
		}),
	},
	play: async ({ canvas }) => {
		await expect(
			await canvas.findByText("We could not find that message. It may have been deleted."),
		).toBeVisible();
		await expect(loadEarlier).not.toHaveBeenCalled();
	},
};

/**
 * Delete asks first, says what stays (the replies in the thread, the copy in Slack), and says that
 * it is final.
 */
export const DeleteMessage: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const row = within(rowOf(canvas.getByText(SLACK_QUESTION.text)));
		await userEvent.click(row.getByRole("button", { name: /^Delete message \(/u }));
		const dialog = await screen.findByRole("alertdialog", { name: "Delete this message?" });
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveAccessibleDescription(
			"Hephaestus forgets it. If it has not answered yet, it does not answer. The replies in its thread stay. It and Hephaestus's answer stay in Slack. You cannot undo this.",
		);
		await userEvent.click(within(dialog).getByRole("button", { name: "Delete message" }));
		await expect(args.actions?.onDelete).toHaveBeenCalledWith(SLACK_QUESTION);
	},
};

/** The live stream keeps failing; the timeline says so while it reconnects. */
export const LiveLost: Story = {
	args: { livePaused: true },
};

/** A weekly digest sits in the conversation like any message, and takes replies in its thread. */
export const Digest: Story = {
	args: { state: ready({ entries: [...CONVERSATION, DIGEST] }) },
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Feedback that reached you" })).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: "Reply in thread (Weekly digest, 5–11 October 2026)" }),
		);
		await expect(args.threads?.onOpen).toHaveBeenCalledWith(DIGEST.id, "composer");
	},
};

/**
 * As the conversation page nests the conversation under its `h1` and `h2`: Hephaestus's headings and a digest's
 * sections are one level below, so a screen reader's list of headings skips none.
 */
export const UnderPageHeadings: Story = {
	args: { state: ready({ entries: [...CONVERSATION, DIGEST] }) },
	render: (args) => (
		<div className="flex h-full flex-col">
			<h1 className="sr-only">Hephaestus</h1>
			<h2 className="sr-only">Your conversation</h2>
			<ConversationTimeline {...args} />
		</div>
	),
	play: async ({ canvas }) => {
		const headings = canvas
			.getAllByRole("heading")
			.map((heading) => `${heading.tagName.toLowerCase()} ${heading.textContent}`);
		await expect(headings).toStrictEqual([
			"h1 Hephaestus",
			"h2 Your conversation",
			"h3 Why",
			"h3 Decision",
			"h3 Feedback that reached you",
			"h3 What changed",
		]);
	},
};

/** A user view: no composer, no votes, no delete, no Reply. Threads still open to read. */
export const ReadOnly: Story = {
	args: { actions: undefined, composer: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("textbox")).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Helpful/u })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Reply in thread/u })).toBeNull();
		await expect(canvas.getByRole("button", { name: /^3 replies/u })).toBeVisible();
	},
};

export const Reflow320: Story = {
	args: { state: ready({ entries: [ANSWER_WITH_FEEDBACK] }) },
	parameters: { viewport: { defaultViewport: "reflow" } },
};
