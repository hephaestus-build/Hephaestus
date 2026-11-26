import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent } from "storybook/test";

import type { ConversationLink } from "@/api/types.gen";

import { AnswerUse } from "./AnswerUse";

const LINKS: ConversationLink[] = [
	{
		kind: "RESULT",
		id: "6b1f0c1e-5a8c-4d42-9b0e-1f1e0d6c1a11",
		text: "Your last PR states how to verify it.",
	},
	{ kind: "PROBLEM", id: "6b1f0c1e-5a8c-4d42-9b0e-1f1e0d6c1a12", text: "The retry has no test." },
];

const meta = {
	component: AnswerUse,
	parameters: { layout: "padded" },
	args: {
		subject: "Hephaestus, 10:42",
		links: LINKS,
		used: {
			recorded: true,
			read: [
				{ kind: "CONTEXT_DOCUMENT", document: "RESULTS_HISTORY", count: 1 },
				{ kind: "REPOSITORY", repository: "acme/web", count: 1 },
				{ kind: "REPOSITORY_FILES", repository: "acme/web", count: 2 },
				{ kind: "CONVERSATION_SEARCH", count: 1 },
			],
			couldNotCheck: [],
		},
	},
} satisfies Meta<typeof AnswerUse>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Closed until opened. Opened, it names what the answer cited and read in the person's terms. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const trigger = canvas.getByRole("button", {
			name: "What Hephaestus used (Hephaestus, 10:42)",
		});
		await expect(canvas.queryByText("Observations of your work")).toBeNull();
		await userEvent.click(trigger);
		await expect(canvas.getByText("one observation, one problem, shown above")).toBeVisible();
		await expect(canvas.getByText("Observations of your work")).toBeVisible();
		await expect(canvas.getByText("2 files in acme/web")).toBeVisible();
		await expect(
			canvas.getByText(/It also read this conversation and the page you asked from\./u),
		).toBeVisible();
	},
};

/** A source that did not open is listed apart, never as something the answer read. */
export const CouldNotCheck: Story = {
	args: {
		links: [],
		used: {
			recorded: true,
			read: [{ kind: "CONTEXT_DOCUMENT", document: "MERGE_READINESS", count: 1 }],
			couldNotCheck: [{ kind: "REPOSITORY", repository: "acme/api", count: 1 }],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(canvas.getByRole("region", { name: "Could not check" })).toHaveTextContent(
			"The repository acme/api",
		);
		await expect(canvas.queryByRole("region", { name: "Cited" })).toBeNull();
	},
};

/** An answer that opened nothing says so, and does not pretend to list the conversation it read. */
export const NothingOpened: Story = {
	args: { links: [], used: { recorded: true, read: [], couldNotCheck: [] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(
			canvas.getByText(
				"Hephaestus opened nothing beyond this conversation and the page you asked from.",
			),
		).toBeVisible();
	},
};

/** An answer whose turn stored no entries says that nothing was recorded. */
export const NotRecorded: Story = {
	args: { links: [], used: { recorded: false, read: [], couldNotCheck: [] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(
			canvas.getByText("Hephaestus did not record what this answer used."),
		).toBeVisible();
	},
};

/** Every kind of source at once, with long repository names. */
export const ManySources: Story = {
	args: {
		used: {
			recorded: true,
			read: [
				{ kind: "CONTEXT_DOCUMENT", document: "RECENT_AUTHORED_WORK", count: 1 },
				{ kind: "CONTEXT_DOCUMENT", document: "DELIVERED_FEEDBACK", count: 1 },
				{ kind: "CONTEXT_DOCUMENT", document: "PRACTICE_CATALOG", count: 1 },
				{
					kind: "REPOSITORY",
					repository: "acme-platform/payments-service-integration-tests",
					count: 1,
				},
				{
					kind: "REPOSITORY_FILES",
					repository: "acme-platform/payments-service-integration-tests",
					count: 12,
				},
				{ kind: "REPOSITORY_FILES", count: 1 },
				{ kind: "CONTEXT_SEARCH", count: 3 },
				{ kind: "CONVERSATION_SEARCH", count: 2 },
			],
			couldNotCheck: [{ kind: "CONTEXT_DOCUMENT", document: "OUTLINE_DOCS", count: 1 }],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		await expect(canvas.getByText("3 searches of your documents")).toBeVisible();
		await expect(canvas.getByText("One file")).toBeVisible();
	},
};

/** At 320 px a long repository name wraps inside the panel. */
export const PhoneWidth: Story = {
	args: ManySources.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /What Hephaestus used/u }));
		const [repository] = canvas.getAllByText(/payments-service-integration-tests/u, {
			selector: "li",
		});
		await expect(repository).toBeVisible();
	},
};
