import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { FieldGroup } from "@/components/ui/field";
import { expectSettledVisible } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";
import { expectClosedSelectShows, expectGenuinelyDisabled } from "@/test/controls";

import {
	connectionFieldsValueOf,
	LlmConnectionFields,
	type LlmConnectionFieldsValue,
} from "./LlmConnectionFields";

/**
 * Both connection dialogs render these fields: the instance catalog's and a workspace's own
 * provider. So nothing here may assume one console.
 *
 * The API is chosen once, at create. A connection keeps it for life, so an edit shows it in a field
 * that cannot change.
 */
const meta = {
	component: LlmConnectionFields,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		value: connectionFieldsValueOf(null),
		onChange: fn(),
		errors: {},
		isEdit: false,
		hasApiKey: false,
	},
	decorators: [
		(Story) => (
			<FieldGroup className="max-w-md">
				<Story />
			</FieldGroup>
		),
	],
	render: (args) => (
		<Stateful<LlmConnectionFieldsValue> initial={args.value}>
			{(value, setValue) => (
				<LlmConnectionFields
					{...args}
					value={value}
					onChange={(next) => {
						setValue(next);
						args.onChange(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof LlmConnectionFields>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ResponsesApi: Story = {
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "API", "Responses API");
	},
};

export const ApiOptions: Story = {
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "API" }));
		const chat = await screen.findByRole("group", { name: "Chat models" });
		await expectSettledVisible(chat);
		await expect(
			within(chat)
				.getAllByRole("option")
				.map((option) => option.textContent),
		).toStrictEqual(["Responses API", "Chat Completions API"]);
		const precompute = screen.getByRole("group", { name: "For precompute scripts" });
		await expect(
			within(precompute)
				.getAllByRole("option")
				.map((option) => option.textContent),
		).toStrictEqual(["Decisions API", "Embeddings API", "Rerank API (Cohere-compatible)"]);
		await expect(screen.getAllByRole("option")).toHaveLength(5);

		await userEvent.click(within(precompute).getByRole("option", { name: "Embeddings API" }));
		await expect(args.onChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ apiProtocol: "openai-embeddings" }),
		);
		await expectClosedSelectShows(canvas, "API", "Embeddings API");
	},
};

export const ChatCompletionsApi: Story = {
	args: { value: { ...connectionFieldsValueOf(null), apiProtocol: "openai-completions" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "API" })).toHaveAccessibleDescription(
			"Use only if the endpoint does not serve the Responses API. It can also serve the decision model if it returns token probabilities and answers without reasoning.",
		);
	},
};

export const DecisionsApi: Story = {
	args: { value: { ...connectionFieldsValueOf(null), apiProtocol: "openai-decisions" } },
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "API", "Decisions API");
		await expect(canvas.getByRole("combobox", { name: "API" })).not.toHaveAccessibleDescription();
	},
};

export const EmbeddingsApi: Story = {
	args: { value: { ...connectionFieldsValueOf(null), apiProtocol: "openai-embeddings" } },
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "API", "Embeddings API");
		await expect(canvas.getByRole("combobox", { name: "API" })).not.toHaveAccessibleDescription();
	},
};

export const RerankApi: Story = {
	args: { value: { ...connectionFieldsValueOf(null), apiProtocol: "cohere-rerank" } },
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "API", "Rerank API (Cohere-compatible)");
		await expect(canvas.getByRole("combobox", { name: "API" })).toHaveAccessibleDescription(
			"Choose a provider that reports token usage, or set No metered API cost.",
		);
	},
};

/**
 * Neither OpenAI nor Azure OpenAI serves a rerank API, so choosing it moves the preset to Other and
 * clears the preset's address. An address the admin typed stays.
 */
export const RerankApiLeavesOpenAiPreset: Story = {
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "Endpoint preset", "OpenAI");
		await expect(canvas.getByLabelText("Base URL")).toHaveValue("https://api.openai.com/v1");
		await userEvent.click(canvas.getByRole("combobox", { name: "API" }));
		await userEvent.click(
			await screen.findByRole("option", { name: "Rerank API (Cohere-compatible)" }),
		);
		await expectClosedSelectShows(canvas, "Endpoint preset", "Other OpenAI-compatible endpoint");
		await expect(canvas.getByLabelText("Base URL")).toHaveValue("");
	},
};

export const RerankApiKeepsTypedUrl: Story = {
	args: {
		value: { ...connectionFieldsValueOf(null), baseUrl: "https://rerank.example.test/v1" },
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "API" }));
		await userEvent.click(
			await screen.findByRole("option", { name: "Rerank API (Cohere-compatible)" }),
		);
		await expectClosedSelectShows(canvas, "Endpoint preset", "Other OpenAI-compatible endpoint");
		await expect(canvas.getByLabelText("Base URL")).toHaveValue("https://rerank.example.test/v1");
	},
};

export const EditReadOnlyApi: Story = {
	args: {
		value: connectionFieldsValueOf({
			displayName: "Embeddings",
			baseUrl: "https://api.openai.com/v1",
			apiProtocol: "openai-embeddings",
			authMode: "BEARER",
		}),
		isEdit: true,
		hasApiKey: true,
		apiKeyLast4: "ab12",
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: "API" })).toBeNull();
		const api = canvas.getByLabelText("API");
		await expect(api).toHaveValue("Embeddings API");
		await expectGenuinelyDisabled(api);
		await expect(
			canvas.getByText("Endpoint, API and authentication cannot change. Add a connection instead."),
		).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByLabelText("Base URL"));
		// The same words as beside the provider on AI models.
		await expect(canvas.getByLabelText("API key")).toHaveAttribute(
			"placeholder",
			"Key ends in ab12",
		);
	},
};
