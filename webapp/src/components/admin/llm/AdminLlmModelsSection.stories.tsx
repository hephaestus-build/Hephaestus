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
	supportsReasoning: true,
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
		keptAfterReply: "NONE",
		dataHandlingNote: "Garching data centre",
		supportsReasoning: false,
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
		dataHandlingTier: "PROVIDER_NOT_KEPT",
		operatedBy: "PROVIDER",
		keptAfterReply: "NONE",
		dataHandlingNote: "EU region, zero-retention agreement renews 2027-01",
	},
	{
		...base,
		id: 3,
		slug: "gpt-5-mini",
		displayName: "GPT-5 mini",
		upstreamModelId: "gpt-5-mini",
		dataHandlingTier: "PROVIDER_KEPT",
		operatedBy: "PROVIDER",
		keptAfterReply: "FOR_SAFETY_CHECKS",
	},
	{
		...base,
		id: 4,
		slug: "unpriced-model",
		displayName: "New model (not priced yet)",
		upstreamModelId: "vendor/new-model",
		dataHandlingTier: "UNDECLARED",
		enabled: false,
		supportsReasoning: false,
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
		connectionDisplayName: "OpenAI production",
		connectionEnabled: true,
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
		await expect(cells.map((row) => row[1])).toStrictEqual([
			"Stays in-house",
			"Provider, nothing kept",
			"Provider, kept for safety checks",
			"Not declared",
		]);
		await expect(cells.map((row) => row[4])).toStrictEqual([
			"Ready",
			"Ready",
			"Ready",
			"Price missing",
		]);
	},
};

export const Empty: Story = {
	args: { models: [] },
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^delete gpt-5$/i }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/can't be deleted/i);
	},
};
