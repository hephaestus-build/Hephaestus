import type { Decorator, Meta, StoryObj } from "@storybook/react-vite";
import { useEffect, useState } from "react";
import { expect, fn, spyOn, waitFor, within } from "storybook/test";

import type { MentorEntry } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { isRecord } from "@/lib/is-record";
import type { PendingMessage } from "@/lib/mentor-entry";

import { earlierToday, minutesBefore } from "@/stories/story-clock";
import { nextFrame } from "@/test/async";

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
	SLACK_QUESTION,
	turn,
	WORKSPACE_SLUG,
} from "./fixtures";
import { Greeting } from "./Greeting";
import { HephComposer } from "./HephComposer";
import { HephTimeline, type HephTimelineProps, type HephTimelineState } from "./HephTimeline";

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
function ColdLoad(props: HephTimelineProps) {
	const [phase, setPhase] = useState<"loading" | "newest" | "all">("loading");
	useEffect(() => {
		const arrive = requestAnimationFrame(() => setPhase("newest"));
		return () => cancelAnimationFrame(arrive);
	}, []);
	let state: HephTimelineState = { status: "loading" };
	if (phase === "newest") {
		state = ready({ earlier: { status: "available", onLoad: () => setPhase("all") } });
	} else if (phase === "all") {
		state = ready({ entries: [...OLDER_PAGE, ...CONVERSATION] });
	}
	return <HephTimeline {...props} state={state} />;
}

/** One state, then the next once the story presses Next, as one cache update brings it. */
function Transition({
	before,
	after,
	...props
}: HephTimelineProps & { before: HephTimelineState; after: HephTimelineState }) {
	const [next, setNext] = useState(false);
	return (
		<div className="flex h-full flex-col">
			<Button variant="outline" onClick={() => setNext(true)}>
				Next
			</Button>
			<div className="min-h-0 flex-1">
				<HephTimeline {...props} state={next ? after : before} />
			</div>
		</div>
	);
}

/** The newest page, and the older one once the timeline asks for it. */
function Paged(props: HephTimelineProps) {
	const [all, setAll] = useState(false);
	const state = all
		? ready({ entries: [...OLDER_PAGE, ...CONVERSATION] })
		: ready({ earlier: { status: "available", onLoad: () => setAll(true) } });
	return <HephTimeline {...props} state={state} />;
}

const meta = {
	component: HephTimeline,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		label: "Conversation with Heph",
		workspaceSlug: WORKSPACE_SLUG,
		state: ready(),
		developer: DEVELOPER,
		onCopy: fn(),
		actions: {
			onVote: fn(),
			onDelete: fn(),
			onStop: fn(),
			onRetrySend: fn(),
			onTryAgain: fn(),
		},
		threads: { panelId: "thread-panel", onOpen: fn() },
		empty: <Greeting />,
		composer: <HephComposer label="Message Heph" placeholder="Message Heph" onSend={fn()} />,
	},
	decorators: [
		(Story) => (
			<div className="h-dvh">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof HephTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Every row left-aligned, a day separator for each local day, Slack-written messages marked, and a
 * thread summary on the message that has replies. Opening it puts focus on the thread's heading.
 */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("region", { name: "Conversation with Heph" })).toBeVisible();
		await expect(canvas.getByText("Yesterday")).toBeVisible();
		await expect(canvas.getByText("Today")).toBeVisible();
		await expect(canvas.getAllByText("Slack")).toHaveLength(2);
		const summary = canvas.getByRole("button", { name: /^3 replies/u });
		// The visible words first, then the message, so two equal summaries keep apart.
		await expect(summary).toHaveAccessibleName(
			/^3 replies · Last reply about 1 hour ago \(Ada Lovelace, .+\)$/u,
		);
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

export const Empty: Story = {
	args: { state: ready({ entries: [] }) },
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

const retryLoad = fn();

export const LoadError: Story = {
	args: { state: { status: "error", onRetry: retryLoad } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(await canvas.findByRole("button", { name: "Retry" }));
		await expect(retryLoad).toHaveBeenCalledOnce();
	},
};

/** Before Heph shows words, its place in the conversation says what it does. */
export const Thinking: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FOLLOW_UP],
			turns: [turn({ id: FOLLOW_UP.turnId ?? "", status: "RUNNING" })],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		const log = canvas.getByRole("log");
		await expect(log).toHaveAttribute("aria-busy", "true");
		await expect(canvas.getByText("Thinking…", { selector: "p" })).toHaveAttribute(
			"role",
			"status",
		);
		await userEvent.click(
			within(log).getByRole("button", { name: /^Stop Heph \(Ada Lovelace, /u }),
		);
		await expect(args.actions?.onStop).toHaveBeenCalledWith(FOLLOW_UP.turnId);
	},
};

/** A follow-up written while Heph answers waits under its question. Its Stop is not the running answer's. */
export const QueuedFollowUp: Story = {
	args: {
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
		await expect(canvas.getByText("Queued. Heph answers this next.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^Stop queued message \(/u }));
		await expect(args.actions?.onStop).toHaveBeenCalledWith(FOLLOW_UP.turnId);
		await userEvent.click(
			within(canvas.getByRole("log")).getByRole("button", { name: /^Stop Heph/u }),
		);
		await expect(args.actions?.onStop).toHaveBeenLastCalledWith("t-running");
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

/** Only the first follow-up in the queue says Heph answers it next. Each Stop names its own message. */
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
		await expect(canvas.getAllByText("Queued. Heph answers this next.")).toHaveLength(1);
		await expect(canvas.getAllByText("Queued.")).toHaveLength(1);
		const stops = canvas.getAllByRole("button", { name: /^Stop queued message \(/u });
		const names = stops.map((button) => button.getAttribute("aria-label"));
		await expect(new Set(names).size).toBe(2);
	},
};

/**
 * Presses Next and checks that `row` took the change in place while the view stayed where the reader
 * was: the scroller sees no new row, so it has nothing to scroll to.
 */
async function expectViewKept(
	viewport: HTMLElement,
	row: HTMLElement,
	pressNext: () => Promise<void>,
	changed: () => Promise<void>,
) {
	await waitFor(async () => {
		await expect(viewport.scrollTop).toBeGreaterThan(0);
	});
	const { scrollTop } = viewport;
	await pressNext();
	await waitFor(changed);
	await nextFrame();
	await nextFrame();
	await expect(row).toBeVisible();
	await expect(viewport.scrollTop).toBeGreaterThanOrEqual(scrollTop - 1);
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
			canvas.getByRole("region", { name: "Conversation with Heph" }),
			row,
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => expect(row).toHaveAttribute("data-message-id", STORED.id),
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
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		const row = rowOf(await canvas.findByText(NEWEST));
		await waitFor(async () => {
			const offset = row.getBoundingClientRect().top - viewport.getBoundingClientRect().top;
			await expect(offset).toBeLessThanOrEqual(viewport.clientHeight / 3);
		});
		await expect(canvasElement.querySelector("[data-message-scroller-spacer]")).not.toBeNull();
	},
};

/** Heph's answer as it streams: three more words at each press of Next. */
function Streams(props: HephTimelineProps) {
	const [words, setWords] = useState(1);
	const text = Array.from({ length: words * 3 }, (_, index) => `word${index}`).join(" ");
	return (
		<div className="flex h-full flex-col">
			<Button variant="outline" onClick={() => setWords((count) => count + 1)}>
				Next
			</Button>
			<div className="min-h-0 flex-1">
				<HephTimeline
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
 * The view never follows the stream: a reader further up keeps their place while Heph writes, and
 * Jump to latest shows until they are at the end again.
 */
export const StreamingKeepsPlace: Story = {
	render: (args) => <Streams {...args} />,
	decorators: SHORT_VIEW,
	play: async ({ canvas, userEvent }) => {
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
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
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
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
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
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
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
		const composer = canvas.getByRole("textbox", { name: "Message Heph" });
		const atEnd = () => viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
		await userEvent.click(canvas.getByRole("button", { name: "Jump to latest" }));
		await waitFor(async () => {
			await expect(atEnd()).toBeLessThanOrEqual(1);
		});
		await userEvent.type(composer, "One{Shift>}{Enter}{/Shift}Two{Shift>}{Enter}{/Shift}Three");
		await waitFor(async () => {
			await expect(atEnd()).toBeLessThanOrEqual(1);
		});

		viewport.scrollTop = 0;
		await nextFrame();
		await userEvent.type(composer, "{Shift>}{Enter}{/Shift}Four{Shift>}{Enter}{/Shift}Five");
		await nextFrame();
		await nextFrame();
		await expect(viewport.scrollTop).toBe(0);
	},
};

/** A follow-up sent while Heph answers is stored and queued in place, and the view stays. */
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
			canvas.getByRole("region", { name: "Conversation with Heph" }),
			row,
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => expect(row).toHaveTextContent("Queued. Heph answers this next."),
		);
	},
};

/** Heph's answer in progress becomes the stored answer in place as the turn ends, and the view stays. */
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
			canvas.getByRole("region", { name: "Conversation with Heph" }),
			row,
			async () => userEvent.click(canvas.getByRole("button", { name: "Next" })),
			async () => expect(row).toHaveTextContent("What made you pick the time-based cache?"),
		);
	},
};

/** Heph could not answer. The question stays, with the reason and Try again. */
export const Failed: Story = {
	args: {
		state: ready({
			entries: [...BEFORE_FOLLOW_UP, FAILED_FOLLOW_UP],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Heph took too long to answer.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^Try again \(/u }));
		await expect(args.actions?.onTryAgain).toHaveBeenCalledWith(FAILED_FOLLOW_UP);
	},
};

/**
 * Heph failed after it wrote part of an answer, such as when the AI model stopped responding. The
 * reason sits under what Heph wrote, and Try again asks the question again.
 */
export const FailedAfterPartOfAnAnswer: Story = {
	args: {
		state: ready({
			entries: [
				...BEFORE_FOLLOW_UP,
				{ ...FAILED_FOLLOW_UP, turnError: "The AI model stopped responding. Try again." },
				{ ...ANSWER_WITH_FEEDBACK, observations: [] },
			],
		}),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("The AI model stopped responding. Try again.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^Try again \(/u }));
		await expect(args.actions?.onTryAgain).toHaveBeenCalledWith(
			expect.objectContaining({ id: FAILED_FOLLOW_UP.id }),
		);
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
		await userEvent.click(canvas.getByRole("button", { name: /^Retry \(/u }));
		await expect(args.actions?.onRetrySend).toHaveBeenCalledWith("r-unsent");
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
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
		const oldest = await canvas.findByText(QUESTION.text);
		await waitFor(async () => {
			await expect(viewport.scrollTop).toBeGreaterThan(0);
		});
		viewport.scrollTop = 0;
		const { top } = oldest.getBoundingClientRect();
		await canvas.findByText("The message the link points to");
		await waitFor(async () => {
			await expect(Math.abs(oldest.getBoundingClientRect().top - top)).toBeLessThan(2);
		});
		await expect(viewport.scrollTop).toBeGreaterThan(0);
	},
};

/** Older messages failed to load; the rest of the conversation stays usable. */
const retryEarlier = fn();

export const EarlierError: Story = {
	args: { state: ready({ earlier: { status: "error", onRetry: retryEarlier } }) },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(await canvas.findByRole("button", { name: "Retry" }));
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
		const viewport = canvas.getByRole("region", { name: "Conversation with Heph" });
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
 * As the mentor page nests the conversation under its `h1` and `h2`: Heph's headings and a digest's
 * sections are one level below, so a screen reader's list of headings skips none.
 */
export const UnderPageHeadings: Story = {
	args: { state: ready({ entries: [...CONVERSATION, DIGEST] }) },
	render: (args) => (
		<div className="flex h-full flex-col">
			<h1 className="sr-only">Heph</h1>
			<h2 className="sr-only">Conversation with Heph</h2>
			<HephTimeline {...args} />
		</div>
	),
	play: async ({ canvas }) => {
		const headings = canvas
			.getAllByRole("heading")
			.map((heading) => `${heading.tagName.toLowerCase()} ${heading.textContent}`);
		await expect(headings).toStrictEqual([
			"h1 Heph",
			"h2 Conversation with Heph",
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
