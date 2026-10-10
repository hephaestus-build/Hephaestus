import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { LlmModel } from "@/api/types.gen";

import { AdminLlmModelsSection } from "./AdminLlmModelsSection";

const price: LlmModel["currentPrice"] = {
	id: 1,
	pricingMode: "PRICED",
	per1mInputUsd: 3,
	per1mOutputUsd: 15,
	currency: "USD",
	effectiveFrom: new Date("2026-05-01T00:00:00Z"),
};

const base = {
	connectionId: 1,
	connectionDisplayName: "OpenAI production",
	enabled: true,
	reasoningEffort: "MEDIUM",
	visibility: "PUBLIC",
	grantedWorkspaceIds: [],
	currentPrice: price,
	createdAt: new Date("2026-05-01T10:00:00Z"),
} satisfies Partial<LlmModel>;

/** One model per declared tier, then one that predates the declaration. */
const mockModels: LlmModel[] = [
	{
		...base,
		id: 1,
		slug: "local-llama",
		displayName: "Local Llama (self-hosted)",
		upstreamModelId: "meta/llama-3-70b",
		dataHandlingTier: "IN_HOUSE",
		operatedBy: "OWN_ORGANISATION",
		dataHandlingNote: "Garching data centre",
		reasoningEffort: undefined,
		visibility: "GRANTED",
		grantedWorkspaceIds: [10, 11],
		currentPrice: {
			id: 2,
			pricingMode: "NO_CHARGE",
			note: "self-hosted, no cost",
			currency: "USD",
			effectiveFrom: new Date("2026-05-01T00:00:00Z"),
		},
	},
	{
		...base,
		id: 2,
		slug: "gpt-5-eu",
		displayName: "GPT-5",
		upstreamModelId: "gpt-5",
		brand: "OPENAI",
		dataHandlingTier: "CLOUD",
		operatedBy: "PROVIDER",
		dataHandlingNote: "EU region, zero-retention agreement renews 2027-01",
	},
	{
		...base,
		id: 4,
		slug: "unpriced-model",
		displayName: "New model (not priced yet)",
		upstreamModelId: "vendor/new-model",
		dataHandlingTier: "UNDECLARED",
		enabled: false,
		reasoningEffort: undefined,
		currentPrice: undefined,
	},
];

/**
 * The table is where an admin sees the declaration land: the badge in the *Data handling* column is
 * the one developers see, and its *Not declared* warning is the whole of the nudge. *Status* stays
 * readiness — price, connection, active, access — so an upgraded instance, where every model is
 * undeclared, still shows which models are off or unpriced.
 */
const meta = {
	component: AdminLlmModelsSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		connection: {
			displayName: "OpenAI production",
			apiProtocol: "openai-responses",
			purposes: ["PRACTICE_REVIEW", "MENTOR"],
			enabled: true,
		},
		workspaceOptions: [
			{ id: 10, displayName: "Teaching team", workspaceSlug: "teaching" },
			{ id: 11, displayName: "Research team", workspaceSlug: "research" },
		],
		models: mockModels,
		mutatingIds: new Set<number>(),
		onAdd: fn(),
		onEdit: fn(),
		onManageAccess: fn(),
		onDelete: fn(),
	},
} satisfies Meta<typeof AdminLlmModelsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const cells = rows.map((row) =>
			within(row)
				.getAllByRole("cell")
				.map((cell) => cell.textContent),
		);
		await expect(cells.map((row) => row[1])).toStrictEqual(["In-house", "Cloud", "Not declared"]);
		await expect(cells.map((row) => row[4])).toStrictEqual(["Ready", "Ready", "Price missing"]);
		// Only the exception is a badge, and it comes from the readiness registry with its icon.
		const missing = canvas.getByText("Price missing").closest("[data-slot=badge]");
		await expect(missing?.querySelector("svg")).not.toBeNull();
		await expect(canvas.getAllByText("Ready")[0]?.closest("[data-slot=badge]")).toBeNull();
		// Each name leads with its maker's mark, as everywhere else a model is named.
		await expect(canvas.getByRole("cell", { name: "GPT-5" }).querySelector("img")).not.toBeNull();
	},
};

/**
 * No model yet: the table keeps its header and says so in one row, the same shape as a workspace's
 * provider. The one way to add a model is the button above it.
 */
export const Empty: Story = {
	args: { models: [] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "Models on OpenAI production" });
		await expect(
			within(table).getByRole("cell", {
				name: "No models yet. Add a model so workspaces can pick it.",
			}),
		).toBeVisible();
		await expect(canvas.getAllByRole("button", { name: /add model/iu })).toHaveLength(1);
	},
};

/**
 * The API sits on a muted line under the heading, with the mark of each kind of model it serves,
 * because it decides which purposes the connection's models serve.
 */
export const PrecomputeConnection: Story = {
	args: {
		connection: {
			displayName: "Embeddings",
			apiProtocol: "openai-embeddings",
			purposes: ["PRACTICE_EMBEDDING"],
			enabled: true,
		},
		models: [],
	},
	play: async ({ canvas }) => {
		const heading = canvas.getByRole("heading", { name: "Models on Embeddings" });
		await expect(heading).toBeVisible();
		await expect(heading.nextElementSibling).toHaveTextContent("Embeddings API");
		await expect(canvas.getByText(", for Embedding model")).toHaveClass("sr-only");
	},
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^delete gpt-5$/iu }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/the delete fails/iu);
	},
};
