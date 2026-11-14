import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { LlmModel } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";

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
	readiness: "READY",
	reasoningEffort: "MEDIUM",
	visibility: "PUBLIC",
	grantedWorkspaceIds: [],
	currentPrice: price,
	limits: { contextWindow: 131_072, maxOutputTokens: 16_384, bounded: true },
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
		readiness: "PRICE_MISSING",
		reasoningEffort: undefined,
		currentPrice: undefined,
	},
	{
		...base,
		id: 5,
		slug: "self-hosted-qwen",
		displayName: "Qwen (no limits known)",
		upstreamModelId: "Qwen/Qwen3.8-27B",
		dataHandlingTier: "IN_HOUSE",
		operatedBy: "OWN_ORGANISATION",
		limits: { bounded: false },
		readiness: "LIMITS_MISSING",
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
		await expect(cells.map((row) => row[1])).toStrictEqual([
			"In-house",
			"Cloud",
			"Not declared",
			"In-house",
		]);
		await expect(cells.map((row) => row[4])).toStrictEqual([
			"Ready",
			"Ready",
			"Price missing",
			"Limits missing",
		]);
		const missing = canvas.getByText("Price missing").closest("[data-slot=badge]");
		await expect(missing?.querySelector("svg")).not.toBeNull();
		await expect(canvas.getAllByText("Ready")[0]?.closest("[data-slot=badge]")).toBeNull();
		await expect(canvas.getByRole("cell", { name: "GPT-5" }).querySelector("img")).not.toBeNull();
	},
};

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

/** The first read of the models: the table keeps its header, so only the rows change when they arrive. */
export const Loading: Story = {
	args: { models: [], isLoading: true },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "Models on OpenAI production" });
		await expect(table).toHaveAttribute("aria-busy", "true");
		for (const header of ["Model", "Data handling", "Price", "Workspace access", "Status"]) {
			await expect(within(table).getByRole("columnheader", { name: header })).toBeVisible();
		}
		await expect(within(table).queryByText(/^No models yet/u)).not.toBeInTheDocument();
	},
};

/** At 320px the table scrolls inside itself, and the page around it does not. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: expectNoPageOverflow,
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^delete gpt-5$/iu }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/the delete fails/iu);
	},
};
