import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { minutesBefore } from "@/stories/story-clock";

import { HephTurnStatus } from "./HephTurnStatus";

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
		stop: { label: "Stop Heph (Ada Lovelace, 10:42)", onStop: fn() },
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

/** Before Heph writes, one line says it is thinking; Stop ends the turn. Early on, nothing counts. */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByText("Thinking…")).toBeVisible();
		await expect(canvas.queryByText(/You can leave/u)).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Stop Heph (Ada Lovelace, 10:42)" }));
		await expect(args.stop?.onStop).toHaveBeenCalledOnce();
	},
};

/** A tool call reads as the stage the host names for it, never as the tool's name. */
export const ToolActivity: Story = {
	args: {
		turn: {
			createdAt: minutesBefore(1),
			status: "RUNNING",
			surface: "APP",
			partial: {
				activity: "TOOL",
				tool: "fetch_context",
				stage: "Reading your practice feedback…",
				text: "",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reading your practice feedback…")).toBeVisible();
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
 * Waiting to start because Heph is busy with other questions. The line says so after a second, so a
 * turn that starts at once never shows it.
 */
export const WaitingToStart: Story = {
	args: { turn: { createdAt: minutesBefore(1), status: "QUEUED", surface: "APP" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Thinking…")).toBeVisible();
		await expect(
			await canvas.findByText("Heph is answering other questions first…", {}, { timeout: 3000 }),
		).toBeVisible();
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
