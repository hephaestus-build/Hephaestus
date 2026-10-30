import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { minutesBefore } from "@/stories/story-clock";

import { HephTurnStatus, type HephTurnStatusProps } from "./HephTurnStatus";

/**
 * When the question was asked, `secondsAgo` before the story runs. The line counts from the real
 * clock, which runs on from the story clock while the suite runs, so the story reads it when it
 * starts instead of at module load.
 */
function askedSecondsAgo(secondsAgo: number) {
	return () => {
		const askedAt = new Date();
		askedAt.setTime(askedAt.getTime() - secondsAgo * 1000);
		return { askedAt };
	};
}

const meta = {
	component: HephTurnStatus,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		turn: {
			createdAt: minutesBefore(1),
			status: "RUNNING",
			surface: "APP",
			partial: { activity: "THINKING", stage: "Thinking…", text: "" },
		},
		stop: { label: "Stop answer (Ada Lovelace, 10:42)", onStop: fn() },
	},
	loaders: [askedSecondsAgo(0)],
	render: (args, { loaded }) => (
		<HephTurnStatus
			{...args}
			turn={{
				...args.turn,
				createdAt: loaded.askedAt instanceof Date ? loaded.askedAt : args.turn.createdAt,
			}}
		/>
	),
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof HephTurnStatus>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Before Hephaestus writes, one line says it is thinking; Stop ends the turn. Early on, nothing counts. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Thinking…")).toBeVisible();
		await expect(canvas.queryByText(/You can leave/u)).toBeNull();
		await userEvent.click(
			canvas.getByRole("button", { name: "Stop answer (Ada Lovelace, 10:42)" }),
		);
		await expect(args.stop?.onStop).toHaveBeenCalledOnce();
	},
};

/** A turn whose tool call is in flight, as the host reports it: the tool and the stage it names. */
function toolTurn(tool: string, stage: string): HephTurnStatusProps["turn"] {
	return {
		createdAt: minutesBefore(1),
		status: "RUNNING",
		surface: "APP",
		partial: { activity: "TOOL", tool, stage, text: "" },
	};
}

/** Tool names a stage must never show, nor the path or command behind them. */
const TOOL_NAMES = /\b(?:read|bash|load_context|search_conversation)\b/u;

/**
 * Hephaestus reads a document from its context folder. The line says what it reads, as the host names
 * it, never the tool or the file's path.
 */
export const ToolActivity: Story = {
	args: { turn: toolTurn("read", "Reading your practice feedback…") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reading your practice feedback…")).toBeVisible();
		await expect(canvas.queryByText(TOOL_NAMES)).toBeNull();
	},
};

/** Hephaestus runs a command on a repository it loaded, such as a search or the history. No command shows. */
export const ReadingARepository: Story = {
	args: { turn: toolTurn("bash", "Reading the repository…") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reading the repository…")).toBeVisible();
		await expect(canvas.queryByText(TOOL_NAMES)).toBeNull();
	},
};

/** Hephaestus loads a repository into its context folder. The line names no repository. */
export const LoadingARepository: Story = {
	args: { turn: toolTurn("load_context", "Loading the repository…") },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading the repository…")).toBeVisible();
		await expect(canvas.queryByText(TOOL_NAMES)).toBeNull();
	},
};

/** The answer so far, above the line. */
export const Streaming: Story = {
	args: {
		turn: {
			createdAt: minutesBefore(1),
			status: "RUNNING",
			surface: "APP",
			partial: {
				activity: "WRITING",
				stage: "Thinking…",
				text: "Your last three pull requests list **what** changed but leave out",
			},
		},
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Writing…")).toBeVisible();
		// The live answer marks its words to fade in; under reduced motion, as this suite runs, none moves.
		const word = canvasElement.querySelector("[data-sd-animate]");
		await expect(word).not.toBeNull();
		await expect(word === null ? undefined : getComputedStyle(word).animationName).toBe("none");
	},
};

/** A turn asked in Slack streams here too. Only the app can stop it. */
export const SlackTurnRunning: Story = {
	args: { turn: { createdAt: minutesBefore(1), status: "RUNNING", surface: "SLACK" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Answering in Slack…")).toBeVisible();
	},
};

/**
 * Waiting to start, and the server cannot tell why: the turn may start at once, or no worker runs.
 * The line says only that it waits, and only after a second, so a turn that starts at once never
 * shows it.
 */
export const WaitingToStart: Story = {
	args: { turn: { createdAt: minutesBefore(1), status: "QUEUED", surface: "APP" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Thinking…")).toBeVisible();
		await expect(await canvas.findByText("Waiting to start…", {}, { timeout: 3000 })).toBeVisible();
	},
};

/**
 * Every Hephaestus slot answers another question, and this one is next in its conversation. The line says
 * so, because a wait of minutes with no reason reads as broken. It blames nobody and promises no
 * time, since nothing can estimate when Hephaestus is free.
 */
export const WaitingForAFreeHeph: Story = {
	args: {
		turn: {
			createdAt: minutesBefore(1),
			status: "QUEUED",
			surface: "APP",
			waitingFor: "FREE_HEPH",
		},
	},
	play: async ({ canvas }) => {
		await expect(
			await canvas.findByText(
				"Hephaestus is answering other questions. Yours starts when one finishes.",
				{},
				{ timeout: 3000 },
			),
		).toBeVisible();
		await expect(canvas.queryByText("Waiting to start…")).toBeNull();
	},
};

/**
 * From 10 s the line counts the wait and says the developer can leave: the answer lands here
 * either way. There is no progress bar, because nothing can estimate the rest.
 */
export const LongWait: Story = {
	loaders: [askedSecondsAgo(75)],
	play: async ({ canvas }) => {
		await expect(
			await canvas.findByText(/^1 min \d+ s · You can leave\. The answer stays here\.$/u),
		).toBeVisible();
		await expect(canvas.queryByRole("progressbar")).toBeNull();
	},
};

/** A read-only conversation shows the work but offers no Stop. */
export const ReadOnly: Story = {
	args: { stop: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /^Stop/u })).toBeNull();
	},
};
