import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent } from "storybook/test";

import { AnswerUse } from "./AnswerUse";

const meta = {
	component: AnswerUse,
	parameters: { layout: "padded" },
	args: {
		subject: "Hephaestus, 10:42",
		text: "Your last pull request states how to verify it, but the retry has no test.",
		used: {
			recorded: true,
			cited: true,
			askedFromPage: true,
			read: [
				{ kind: "CONTEXT_DOCUMENT", document: "RESULTS_HISTORY", count: 1 },
				{ kind: "REPOSITORY", count: 1 },
				{ kind: "FILES", count: 2 },
				{ kind: "CONVERSATION_SEARCH", count: 1 },
			],
			couldNotCheck: [],
		},
	},
} satisfies Meta<typeof AnswerUse>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Closed until opened. Opened, it counts what the answer read in the person's terms and names no repository, because a
 * name could outlive the person's access.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("button", {
			name: "What Hephaestus used (Hephaestus, 10:42: Your last pull request states how to verify it,…)",
		});
		await expect(canvas.queryByText("Observations of your work")).toBeNull();
		await userEvent.click(trigger);
		await expect(
			canvas.getByText("The observations and feedback that this answer links to"),
		).toBeVisible();
		await expect(canvas.getByText("Observations of your work")).toBeVisible();
		await expect(canvas.getByText("One repository")).toBeVisible();
		await expect(canvas.getByText("2 files")).toBeVisible();
		await expect(
			canvas.getByText(/It also read this conversation and the page you asked from\./u),
		).toBeVisible();
	},
};

/** A source that did not open, and a document with no known name, are listed apart from what the answer read. */
export const CouldNotCheck: Story = {
	args: {
		used: {
			recorded: true,
			cited: false,
			askedFromPage: true,
			read: [{ kind: "CONTEXT_DOCUMENT", document: "MERGE_READINESS", count: 1 }],
			couldNotCheck: [
				{ kind: "REPOSITORY", count: 1 },
				{ kind: "CONTEXT_DOCUMENT", count: 1 },
			],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		const couldNot = canvas.getByRole("region", { name: "Could not check" });
		await expect(couldNot).toHaveTextContent("One repository");
		await expect(couldNot).toHaveTextContent("Another document about your work");
		await expect(canvas.queryByRole("region", { name: "Cited" })).toBeNull();
	},
};

/** An answer that opened nothing says so, and does not pretend to list the conversation it read. */
export const NothingOpened: Story = {
	args: {
		used: { recorded: true, cited: false, askedFromPage: true, read: [], couldNotCheck: [] },
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(
			canvas.getByText(
				"Hephaestus opened nothing beyond this conversation and the page you asked from.",
			),
		).toBeVisible();
	},
};

/** A question asked in Slack has no app page, so the line names only the conversation. */
export const AskedInSlack: Story = {
	args: {
		used: {
			recorded: true,
			cited: false,
			askedFromPage: false,
			read: [{ kind: "COMMAND", count: 2 }],
			couldNotCheck: [],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(canvas.getByText("2 commands over these files")).toBeVisible();
		await expect(canvas.getByText(/It also read this conversation\.$/u)).toBeVisible();
		await expect(canvas.queryByText(/the page you asked from/u)).toBeNull();
	},
};

/** An answer whose turn stored no entries says that nothing was recorded, and still says what it links to. */
export const NotRecorded: Story = {
	args: {
		used: { recorded: false, cited: true, askedFromPage: false, read: [], couldNotCheck: [] },
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(
			canvas.getByText("Hephaestus did not record what this answer used."),
		).toBeVisible();
		await expect(canvas.getByRole("region", { name: "Cited" })).toBeVisible();
	},
};

/** A kind or a document that this build does not know gets a neutral name, never a guess. */
export const UnknownKinds: Story = {
	args: {
		used: {
			recorded: true,
			cited: false,
			askedFromPage: false,
			read: [
				{ kind: "A_KIND_FROM_A_NEWER_SERVER", count: 1 },
				{ kind: "CONTEXT_DOCUMENT", document: "toString", count: 2 },
			],
			couldNotCheck: [],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(canvas.getByText("Other sources")).toBeVisible();
		await expect(canvas.getByText("2 other documents about your work")).toBeVisible();
	},
};

/** Every kind of source at once. */
export const ManySources: Story = {
	args: {
		used: {
			recorded: true,
			cited: true,
			askedFromPage: true,
			read: [
				{ kind: "CONTEXT_DOCUMENT", document: "RECENT_AUTHORED_WORK", count: 1 },
				{ kind: "CONTEXT_DOCUMENT", document: "DELIVERED_FEEDBACK", count: 1 },
				{ kind: "CONTEXT_DOCUMENT", document: "PREPARED_CONVERSATION_FEEDBACK", count: 1 },
				{ kind: "REPOSITORY", count: 3 },
				{ kind: "FILES", count: 12 },
				{ kind: "COMMAND", count: 1 },
				{ kind: "CONVERSATION_SEARCH", count: 2 },
			],
			couldNotCheck: [{ kind: "CONTEXT_DOCUMENT", document: "OUTLINE_DOCS", count: 1 }],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(canvas.getByText("3 repositories")).toBeVisible();
		await expect(canvas.getByText("One command over these files")).toBeVisible();
	},
};

/** At 320 px the longest document name wraps inside the panel. */
export const PhoneWidth: Story = {
	args: ManySources.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(
			canvas.getByText("Practice feedback prepared for this conversation"),
		).toBeVisible();
	},
};
