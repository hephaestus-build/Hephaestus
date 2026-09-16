import type { Meta, StoryObj } from "@storybook/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, fn } from "storybook/test";

import type { OutlineCollection } from "@/api/types.gen";
import { daysBefore, minutesBefore } from "@/components/common/story-clock";

import { OutlineCollectionsSection } from "./OutlineCollectionsSection";

/**
 * Admin surface for the mirrored-collections plane of the Outline integration. Presentational:
 * the lifecycle mutations are delegated to the container, so these stories mock the callbacks
 * with `fn()`. The Add-collection dialog issues a lazy candidates query (only while open), so
 * the component is wrapped in a fresh QueryClient and candidate-driven stories bring their own
 * MSW handler.
 */
const meta = {
	component: OutlineCollectionsSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<QueryClientProvider
				client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
			>
				<Story />
			</QueryClientProvider>
		),
	],
	args: {
		workspaceSlug: "demo-workspace",
		collections: [],
		isLoading: false,
		onRegisterCollection: fn(),
		onUpdateCollectionState: fn(),
		onRemoveCollection: fn(),
	},
} satisfies Meta<typeof OutlineCollectionsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

const engineering: OutlineCollection = {
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

const decisions: OutlineCollection = {
	id: 2,
	collectionId: "col-decisions",
	name: "Architecture Decisions",
	urlId: "adr-9kQ2p",
	icon: "📐",
	state: "ENABLED",
	syncStatus: "PENDING",
	documentCount: 14,
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
		canvas.getByText(/couldn't load the mirrored collections/iu);
		canvas.getByText(/outline sync is unavailable/iu);
		canvas.getByRole("button", { name: /^retry$/iu });
	},
};
