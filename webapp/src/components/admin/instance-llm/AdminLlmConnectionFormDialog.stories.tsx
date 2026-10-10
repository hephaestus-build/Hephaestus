import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { LlmConnection } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { expectDialogFitsViewport } from "@/stories/reflow";

import {
	AdminLlmConnectionFormDialog,
	type AdminLlmConnectionFormDialogProps,
} from "./AdminLlmConnectionFormDialog";

const mockConnection: LlmConnection = {
	id: 1,
	slug: "openai-production",
	displayName: "OpenAI production",
	authMode: "BEARER",
	apiProtocol: "openai-responses",
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
	baseUrl: "https://openai-production.example.com/openai",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-05-01T10:00:00Z"),
};

const meta = {
	component: AdminLlmConnectionFormDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		editing: null,
		isSubmitting: false,
		onCreate: fn(),
		onUpdate: fn(),
		onProbe: fn(),
		isProbing: false,
		onProbed: fn(),
	},
} satisfies Meta<typeof AdminLlmConnectionFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AddConnection: Story = {};

export const EditConnection: Story = {
	args: { editing: mockConnection },
};

export const AddRerankConnection: Story = {
	play: async ({ args }) => {
		await userEvent.type(await screen.findByLabelText("Display name"), "Reranker");
		await userEvent.click(screen.getByRole("combobox", { name: "API" }));
		await userEvent.click(
			await screen.findByRole("option", { name: "Rerank API (Cohere-compatible)" }),
		);
		await expect(screen.getByRole("combobox", { name: "API" })).toHaveAccessibleDescription(
			"Choose a provider that reports token usage, or set No metered API cost.",
		);
		// OpenAI serves no rerank API: the preset moves to Other and the admin names the endpoint.
		const baseUrl = screen.getByLabelText("Base URL");
		await expect(baseUrl).toHaveValue("");
		await userEvent.type(baseUrl, "https://rerank.example.test/v1");
		await userEvent.click(screen.getByRole("button", { name: "Add connection" }));
		await expect(args.onCreate).toHaveBeenCalledWith(
			expect.objectContaining({
				displayName: "Reranker",
				apiProtocol: "cohere-rerank",
				baseUrl: "https://rerank.example.test/v1",
			}),
		);
	},
};

const embeddingsConnection: LlmConnection = {
	...mockConnection,
	id: 2,
	slug: "embeddings",
	displayName: "Embeddings",
	apiProtocol: "openai-embeddings",
	purposes: ["PRACTICE_EMBEDDING"],
};

export const EditEmbeddingsConnection: Story = {
	args: { editing: embeddingsConnection },
	play: async () => {
		const api = await screen.findByLabelText("API");
		await expectSettledVisible(api);
		await expect(api).toHaveValue("Embeddings API");
		await expect(screen.queryByRole("combobox", { name: "API" })).toBeNull();
	},
};

/**
 * A precompute endpoint may serve no `{baseUrl}/models`. When it answers that the list is not
 * there, the test is no fault, and the result says so in the status region.
 */
export const EmbeddingsProbeListsNoModels: Story = {
	args: {
		editing: embeddingsConnection,
		onProbeSaved: fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbeSaved"]>>(
			(_id, callbacks) =>
				callbacks.onSuccess({
					reachable: false,
					models: [],
					statusCode: 404,
					message: "The provider answered with HTTP 404.",
				}),
		),
	},
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Test saved connection" }));
		const result = await screen.findByText(
			"No model list at this address. Add the model by its ID. If its calls fail too, check the base URL.",
		);
		await expectSettledVisible(result);
		await expect(screen.getByRole("status")).toContainElement(result);
		await expect(screen.queryByRole("alert")).toBeNull();
		await expect(screen.getByRole("button", { name: "Save changes" })).toBeEnabled();
	},
};

/** A refused key on a precompute endpoint is a real fault, so it keeps the server's words. */
export const EmbeddingsProbeRefused: Story = {
	args: {
		editing: embeddingsConnection,
		onProbeSaved: fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbeSaved"]>>(
			(_id, callbacks) =>
				callbacks.onSuccess({
					reachable: false,
					models: [],
					statusCode: 401,
					message: "The provider answered with HTTP 401.",
				}),
		),
	},
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Test saved connection" }));
		const failure = await screen.findByRole("alert");
		await expectSettledVisible(failure);
		await expect(failure).toHaveTextContent(
			"We could not fetch the model list. The provider answered with HTTP 401. You can still save the connection and enter a model ID.",
		);
		await expect(screen.queryByText(/No model list at this address/u)).toBeNull();
	},
};

export const Probing: Story = {
	args: { isProbing: true },
};

export const DiscoveryUnsupported: Story = {
	args: {
		onProbe: fn<AdminLlmConnectionFormDialogProps["onProbe"]>((_request, callbacks) =>
			callbacks.onSuccess({
				reachable: false,
				models: [],
				statusCode: 404,
				message: "The provider answered with HTTP 404.",
			}),
		),
	},
	play: async () => {
		const baseUrl = await screen.findByLabelText("Base URL");
		await userEvent.clear(baseUrl);
		await userEvent.type(baseUrl, "https://example.com");
		await userEvent.click(screen.getByRole("button", { name: "Test and fetch models" }));
		await expectSettledVisible(await screen.findByText(/could not fetch the model list/iu));
		await expect(screen.getByRole("button", { name: "Add connection" })).toBeEnabled();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Add connection" }));
		await expectSettledVisible(await screen.findByText(/enter a display name/iu));
	},
};

/** At the WCAG 2.2 SC 1.4.10 reflow width (320 px): the dialog must stay inside the viewport. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		await screen.findByRole("button", { name: "Add connection" });
		await expectDialogFitsViewport();
	},
};
