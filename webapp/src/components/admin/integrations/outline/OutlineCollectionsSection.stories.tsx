import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { OutlineCollection } from "@/api/types.gen";
import { daysBefore, minutesBefore } from "@/stories/story-clock";

import { OutlineCollectionsSection } from "./OutlineCollectionsSection";

/**
 * Admin surface for the mirrored-collections plane of the Outline integration. Presentational:
 * the lifecycle mutations are delegated to the container, so these stories mock the callbacks
 * with `fn()`. The Add collections dialog and its candidates come in as props too.
 */
const meta = {
	component: OutlineCollectionsSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		collections: [],
		isLoading: false,
		onRegisterCollection: fn(),
		onUpdateCollectionState: fn(),
		onRemoveCollection: fn(),
		addOpen: false,
		onAddOpenChange: fn(),
		collectionCandidates: { isLoading: true, onRetry: fn() },
	},
} satisfies Meta<typeof OutlineCollectionsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

const engineering: OutlineCollection = {
	id: "1",
	collectionId: "col-engineering",
	name: "Engineering",
	urlId: "engineering-4nZ3x",
	color: "#4E5C6E",
	icon: null,
	description: null,
	state: "ENABLED",
	syncStatus: "COMPLETE",
	documentCount: 87,
	documentsUpstream: 87,
	exportsSkippedForBudget: null,
	lastSyncedAt: minutesBefore(12),
	lastSyncError: null,
	createdAt: daysBefore(30),
};

const decisions: OutlineCollection = {
	id: "2",
	collectionId: "col-decisions",
	name: "Architecture Decisions",
	urlId: "adr-9kQ2p",
	icon: "📐",
	color: null,
	description: null,
	state: "ENABLED",
	syncStatus: "PENDING",
	documentCount: 14,
	documentsUpstream: null,
	exportsSkippedForBudget: null,
	lastSyncedAt: null,
	lastSyncError: null,
	createdAt: minutesBefore(5),
};

/** First run — no collections yet; the empty state offers an Add affordance. */
export const Empty: Story = {
	args: { collections: [] },
	play: async ({ canvas }) => {
		canvas.getByText(/no collections mirrored yet/iu);
		await expect(
			canvas.getAllByRole("button", { name: /add collection/iu }).length,
		).toBeGreaterThan(1);
	},
};

/**
 * Mixed list — an up-to-date collection and one still on its first sync pass.
 *
 * The table is management-only: no Documents column and no Last-synced column, because those are the
 * shared sync ledger's above this card, where they can be judged against the connection's cadence.
 */
export const Populated: Story = {
	args: { collections: [engineering, decisions] },
	play: async ({ canvas }) => {
		canvas.getByText("Engineering");
		canvas.getByText(/up to date/iu);
		canvas.getByText(/syncing…/iu);

		await expect(
			canvas.queryByRole("columnheader", { name: /documents/iu }),
		).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("columnheader", { name: /last synced/iu }),
		).not.toBeInTheDocument();
		await expect(canvas.queryByText("87")).not.toBeInTheDocument();
	},
};

/** Loading — skeleton rows while the collection list resolves. */
export const Loading: Story = {
	args: { isLoading: true, collections: [] },
};

/** The collection-list query failed — the shared error alert with Retry, not the empty state. */
export const LoadError: Story = {
	args: {
		collections: [],
		error: { status: 503, title: "Service Unavailable", detail: "Outline sync is unavailable." },
		onRetry: fn(),
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/no collections mirrored yet/iu)).not.toBeInTheDocument();
		canvas.getByText(/could not load the mirrored collections/iu);
		canvas.getByText(/outline sync is unavailable/iu);
		canvas.getByRole("button", { name: /^retry$/iu });
	},
};

/** The collection list was refused: it says so, offers no Retry, and lists no collection to remove. */
export const Refused: Story = {
	args: { collections: [], error: { status: 403 }, onRetry: fn() },
	play: async ({ canvas }) => {
		canvas.getByText("You do not have access to the mirrored collections");
		await expect(canvas.queryByRole("button", { name: /^retry$/iu })).not.toBeInTheDocument();
		await expect(canvas.queryByText(/no collections mirrored yet/iu)).not.toBeInTheDocument();
	},
};

/** A failed refresh keeps the collections last read, and their actions, under the failure. */
export const RefreshFailed: Story = {
	args: {
		collections: [engineering, decisions],
		refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		canvas.getByText("The mirrored collections may be out of date");
		canvas.getByRole("button", { name: /actions for engineering/iu });

		await expect(canvas.getByRole("button", { name: /^retry$/iu })).toBeEnabled();
	},
};

export const RefreshRetrying: Story = {
	args: {
		collections: [engineering, decisions],
		refreshFailure: { error: { status: 503 }, pending: true, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Retrying…" })).toBeDisabled();
		canvas.getByRole("button", { name: /actions for engineering/iu });
	},
};

/** Remove opens from the row and names the collection, with the count it erases, as the list reads it. */
export const Remove: Story = {
	args: { collections: [engineering, decisions] },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /actions for engineering/iu }));
		await userEvent.click(await screen.findByRole("menuitem", { name: /remove and erase/iu }));
		const dialog = within(
			await screen.findByRole("alertdialog", {
				name: "Remove “Engineering” and erase its mirror?",
			}),
		);
		dialog.getByText(/all 87 mirrored documents/iu);

		await userEvent.click(dialog.getByRole("button", { name: /^remove and erase$/iu }));
		await waitFor(async () => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
	},
};
