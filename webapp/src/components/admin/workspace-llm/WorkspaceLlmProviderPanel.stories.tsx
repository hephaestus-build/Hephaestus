import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { WorkspaceLlmConnection, WorkspaceLlmModel } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";

import { WorkspaceLlmProviderPanel } from "./WorkspaceLlmProviderPanel";

const openAi: WorkspaceLlmConnection = {
	id: 1,
	slug: "my-openai",
	displayName: "My OpenAI account",
	authMode: "BEARER",
	apiProtocol: "openai-responses",
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
	baseUrl: "https://api.openai.com/v1",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

const embeddings: WorkspaceLlmConnection = {
	...openAi,
	id: 2,
	slug: "embeddings",
	displayName: "On-prem embeddings",
	apiProtocol: "openai-embeddings",
	purposes: ["PRACTICE_EMBEDDING"],
	baseUrl: "https://embed.example.test/v1",
	hasApiKey: false,
	apiKeyLast4: undefined,
};

const gpt: WorkspaceLlmModel = {
	id: 10,
	connectionId: openAi.id,
	connectionDisplayName: openAi.displayName,
	slug: "gpt-5-mini",
	displayName: "GPT-5 mini",
	upstreamModelId: "openai/gpt-5-mini",
	brand: "OPENAI",
	dataHandlingTier: "CLOUD",
	operatedBy: "PROVIDER",
	enabled: true,
	pricingMode: "PRICED",
	per1mInputUsd: 0.25,
	per1mOutputUsd: 2,
	currency: "USD",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

function columnLeft(region: HTMLElement, column: string): number {
	return within(region).getByRole("columnheader", { name: column }).getBoundingClientRect().left;
}

/**
 * The workspace's own providers on AI models, below the assignments. The route owns every request,
 * so this panel only draws what it is handed and reports what the admin asked for.
 */
const meta = {
	component: WorkspaceLlmProviderPanel,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", connections: [openAi, embeddings], models: [gpt] },
		registrationAllowed: true,
		testResults: new Map(),
		testingConnectionIds: new Set(),
		writingConnectionIds: new Set(),
		writingModelIds: new Set(),
		onAddConnection: fn(),
		onEditConnection: fn(),
		onTestConnection: fn(),
		onDisconnect: fn(),
		onAddModel: fn(),
		onEditModel: fn(),
		onDeleteModel: fn(),
	},
} satisfies Meta<typeof WorkspaceLlmProviderPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * One section. Each provider is a heading with what it speaks, its key and its actions, and its
 * models one bordered table: no card around the table, and no box inside it.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Your providers" })).toBeVisible();
		const chat = within(canvas.getByRole("region", { name: "My OpenAI account" }));
		await expect(chat.getByText("Responses API")).toBeVisible();
		await expect(chat.getByText("Key ends in ab12")).toBeVisible();
		await expect(chat.getByRole("table", { name: "Models on My OpenAI account" })).toBeVisible();
		const precompute = within(canvas.getByRole("region", { name: "On-prem embeddings" }));
		await expect(precompute.getByText("Embeddings API")).toBeVisible();
		await expect(precompute.getByText(", for Embedding model")).toHaveClass("sr-only");
		await expect(
			precompute.getByRole("cell", { name: "No models yet. Add a model to use this provider." }),
		).toBeVisible();
		await expect(
			precompute.getByRole("button", { name: "Add model to On-prem embeddings" }),
		).toBeVisible();
		// The server keeps a provider that still has models, so its Disconnect says why it is off.
		const keeps = chat.getByRole("button", { name: "Disconnect My OpenAI account" });
		await expect(keeps).toBeDisabled();
		await expect(keeps).toHaveAccessibleDescription("To disconnect, delete its models first.");
		await expect(chat.getByText("To disconnect, delete its models first.")).toBeVisible();
		await expect(
			precompute.getByRole("button", { name: "Disconnect On-prem embeddings" }),
		).toBeEnabled();
		await expect(precompute.queryByText("To disconnect, delete its models first.")).toBeNull();
		// Stacked tables keep their columns in the same places, an empty one too.
		for (const column of ["Data handling", "Price", "Status", "Actions"]) {
			await expect(
				columnLeft(canvas.getByRole("region", { name: "On-prem embeddings" }), column),
			).toBe(columnLeft(canvas.getByRole("region", { name: "My OpenAI account" }), column));
		}
	},
};

/** A provider turned off wears the registry's badge, and none of its models reads as ready. */
export const ProviderOff: Story = {
	args: {
		state: {
			status: "ready",
			connections: [{ ...openAi, enabled: false }, embeddings],
			models: [gpt],
		},
	},
	play: async ({ canvas }) => {
		const off = within(canvas.getByRole("region", { name: "My OpenAI account" }));
		await expect(off.getByText("Off")).toBeVisible();
		await expect(off.getByRole("cell", { name: "Connection off" })).toBeVisible();
		await expect(off.queryByText("Ready")).toBeNull();
		const on = within(canvas.getByRole("region", { name: "On-prem embeddings" }));
		await expect(on.queryByText("Off")).toBeNull();
	},
};

/** No provider yet: the section keeps its heading, and the empty state names any API, not one. */
export const Empty: Story = {
	args: { state: { status: "ready", connections: [], models: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Your providers" })).toBeVisible();
		await expect(canvas.getByText("Connect your own provider")).toBeVisible();
		await expect(canvas.getAllByRole("button", { name: "Add provider" })).toHaveLength(1);
	},
};

/** A provider's shape while the providers load, under the section's own heading, so the page below does not jump. */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.queryByRole("status")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		state: { status: "error", error: { status: 503, title: "Unavailable" }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your AI providers")).toBeVisible();
		await expect(canvas.queryByText("Connect your own provider")).toBeNull();
	},
};

/** The instance stopped new providers: existing ones stay editable, and nothing offers to add one. */
export const RegistrationBlocked: Story = {
	args: { registrationAllowed: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("New workspace providers and models are disabled")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Add provider" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Add model/u })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Edit My OpenAI account" })).toBeEnabled();
	},
};

/**
 * A precompute endpoint often serves no model list, so its failed test is no fault; a chat endpoint
 * that fails the same way is one.
 */
export const TestResults: Story = {
	args: {
		testResults: new Map([
			[openAi.id, { status: "answered", result: { reachable: true, modelCount: 12 } }],
			[
				embeddings.id,
				{
					status: "answered",
					result: { reachable: false, modelCount: 0, statusCode: 404, message: "HTTP 404" },
				},
			],
		]),
	},
	play: async ({ canvas }) => {
		const chat = within(canvas.getByRole("region", { name: "My OpenAI account" }));
		await expect(chat.getByRole("status")).toHaveTextContent("Connected. 12 models available.");
		const precompute = within(canvas.getByRole("region", { name: "On-prem embeddings" }));
		await expect(precompute.getByRole("status")).toHaveTextContent(
			/^No model list at this address/u,
		);
		await expect(precompute.queryByRole("alert")).toBeNull();
	},
};

export const Testing: Story = {
	args: { testingConnectionIds: new Set([openAi.id]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Testing… My OpenAI account" })).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Test connection to On-prem embeddings" }),
		).toBeEnabled();
	},
};
