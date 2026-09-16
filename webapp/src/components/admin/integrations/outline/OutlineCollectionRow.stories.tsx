import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { OutlineCollection } from "@/api/types.gen";
import { daysBefore, minutesBefore } from "@/components/common/story-clock";
import { Table, TableBody } from "@/components/ui/table";
import { expectSettledVisible } from "@/test/overlay";

import { OutlineCollectionRow } from "./OutlineCollectionRow";

/**
 * One mirrored Outline collection. A `<tr>` is only valid inside a table, so every story wraps the
 * row in a minimal `Table`. Pure — pause / resume / remove are delegated upward.
 *
 * This row is the management plane only: document counts and freshness live in the shared
 * `SyncResourcesTable` the Outline page mounts above the collections card, so the same fact is never
 * printed twice from two independently-polled queries in two different visual languages.
 */
const meta = {
	component: OutlineCollectionRow,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<Table>
				<TableBody>
					<Story />
				</TableBody>
			</Table>
		),
	],
	args: {
		onPause: fn(),
		onResume: fn(),
		onRemove: fn(),
	},
} satisfies Meta<typeof OutlineCollectionRow>;

export default meta;
type Story = StoryObj<typeof meta>;

const base: OutlineCollection = {
	id: 1,
	collectionId: "col-engineering",
	name: "Engineering",
	urlId: "engineering-4nZ3x",
	color: "#4E5C6E",
	state: "ENABLED",
	syncStatus: "COMPLETE",
	documentCount: 87,
	lastSyncedAt: minutesBefore(12),
	createdAt: daysBefore(30),
};

/** Steady state — mirroring, up to date, Pause offered in the row menu. */
export const Mirroring: Story = {
	args: { collection: base },
	play: async ({ canvas }) => {
		canvas.getByText("Mirroring");
		canvas.getByText(/up to date/iu);
		canvas.getByText("engineering-4nZ3x");
		// Freshness and document count live in the sync ledger, not this row — assert their absence.
		await expect(canvas.queryByRole("button", { name: /ago$/iu })).not.toBeInTheDocument();
		await expect(canvas.queryByText("87")).not.toBeInTheDocument();

		await userEvent.click(canvas.getByRole("button", { name: /actions for engineering/iu }));
		await expectSettledVisible(await screen.findByRole("menuitem", { name: /^pause$/iu }));
	},
};

/** Paused — syncing frozen, documents kept; the menu offers Resume instead of Pause. */
export const Paused: Story = {
	args: {
		collection: { ...base, id: 2, collectionId: "col-handbook", name: "Handbook", state: "PAUSED" },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Paused");

		await userEvent.click(canvas.getByRole("button", { name: /actions for handbook/iu }));
		await expectSettledVisible(await screen.findByRole("menuitem", { name: /resume/iu }));
		await expect(screen.queryByRole("menuitem", { name: /^pause$/iu })).not.toBeInTheDocument();
	},
};

/** First pass still running — a PENDING collection with no clean sync yet. */
export const Syncing: Story = {
	args: {
		collection: {
			...base,
			id: 3,
			collectionId: "col-decisions",
			name: "Architecture Decisions",
			urlId: undefined,
			icon: "📐",
			syncStatus: "PENDING",
			documentCount: 0,
			lastSyncedAt: undefined,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText(/syncing…/iu);
		// No urlId ⇒ no subtitle; the raw UUID is never shown.
		await expect(canvas.queryByText(/col-decisions/u)).not.toBeInTheDocument();
		// "Never synced" is the sync ledger's reading, not this row's.
		await expect(canvas.queryByText("Never")).not.toBeInTheDocument();
	},
};

/** The last sync failed — the detail opens on click (a tooltip never opens on touch) and is selectable. */
export const SyncError: Story = {
	args: {
		collection: {
			...base,
			id: 4,
			collectionId: "col-legacy",
			name: "Legacy Wiki",
			urlId: undefined,
			syncStatus: "PENDING",
			documentCount: 3,
			lastSyncError: "Outline API returned 403 for collections.info — the bot user lost access.",
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /sync error for legacy wiki/iu }));
		await expectSettledVisible(await screen.findByText(/the bot user lost access/iu));
	},
};

/**
 * The last pass hit the shared export budget. The skip is a property of that pass, so it sits with the
 * pass's other outcome (the sync error) in the Sync cell rather than beside a coverage count — that
 * count is the ledger's to report.
 */
export const BudgetSkipped: Story = {
	args: {
		collection: {
			...base,
			id: 5,
			collectionId: "col-research",
			name: "Research Notes",
			urlId: "research-8pL4m",
			documentCount: 480,
			documentsUpstream: 512,
			exportsSkippedForBudget: 32,
			lastSyncedAt: minutesBefore(30),
		},
	},
	play: async ({ canvas }) => {
		// The coverage pair is the ledger's; the warning that qualifies it stays with the pass.
		await expect(canvas.queryByText("480")).not.toBeInTheDocument();
		await expect(canvas.queryByText(/\/ 512/u)).not.toBeInTheDocument();

		await userEvent.click(
			canvas.getByRole("button", { name: /32 exports skipped for budget for research notes/iu }),
		);
		await expectSettledVisible(await screen.findByText(/catch up on the next reconcile/iu));
	},
};
