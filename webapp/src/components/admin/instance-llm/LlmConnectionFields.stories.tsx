import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import { FieldGroup } from "@/components/ui/field";
import { expectSettledVisible } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";
import { expectClosedSelectShows, expectGenuinelyDisabled } from "@/test/controls";

import {
	connectionFieldsValueOf,
	LlmConnectionFields,
	type LlmConnectionFieldsValue,
} from "./LlmConnectionFields";

// Shared fields serve both admin consoles; a connection's API is immutable after creation.
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
		const completions = await screen.findByRole("option", { name: "Chat Completions API" });
		await expectSettledVisible(completions);
		await expect(screen.getAllByRole("option").map((option) => option.textContent)).toStrictEqual([
			"Responses API",
			"Chat Completions API",
			"Messages API (Anthropic)",
		]);

		await userEvent.click(completions);
		await expect(args.onChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ apiProtocol: "openai-completions" }),
		);
		await expectClosedSelectShows(canvas, "API", "Chat Completions API");
	},
};

export const ChatCompletionsApi: Story = {
	args: { value: { ...connectionFieldsValueOf(null), apiProtocol: "openai-completions" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("combobox", { name: "API" })).toHaveAccessibleDescription(
			"Use only if the endpoint does not serve the Responses API.",
		);
	},
};

// Only Anthropic serves its Messages API; switching presets must preserve an administrator's typed URL.
export const MessagesApiSwitchesToAnthropicPreset: Story = {
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "Endpoint preset", "OpenAI");
		await expect(canvas.getByLabelText("Base URL")).toHaveValue("https://api.openai.com/v1");
		await userEvent.click(canvas.getByRole("combobox", { name: "API" }));
		await userEvent.click(await screen.findByRole("option", { name: "Messages API (Anthropic)" }));
		await expectClosedSelectShows(canvas, "Endpoint preset", "Anthropic");
		await expect(canvas.getByLabelText("Base URL")).toHaveValue("https://api.anthropic.com");
	},
};

export const MessagesApiKeepsTypedUrl: Story = {
	args: {
		value: { ...connectionFieldsValueOf(null), baseUrl: "https://llm.example.test/v1" },
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "API" }));
		await userEvent.click(await screen.findByRole("option", { name: "Messages API (Anthropic)" }));
		await expectClosedSelectShows(canvas, "Endpoint preset", "Anthropic");
		await expect(canvas.getByLabelText("Base URL")).toHaveValue("https://llm.example.test/v1");
	},
};

export const EditReadOnlyApi: Story = {
	args: {
		value: connectionFieldsValueOf({
			displayName: "Completions",
			baseUrl: "https://api.openai.com/v1",
			apiProtocol: "openai-completions",
			authMode: "BEARER",
		}),
		isEdit: true,
		hasApiKey: true,
		apiKeyLast4: "ab12",
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("combobox", { name: "API" })).toBeNull();
		const api = canvas.getByLabelText("API");
		await expect(api).toHaveValue("Chat Completions API");
		await expectGenuinelyDisabled(api);
		await expect(
			canvas.getByText("Endpoint, API and authentication cannot change. Add a connection instead."),
		).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByLabelText("Base URL"));
		await expect(canvas.getByLabelText("API key")).toHaveAttribute(
			"placeholder",
			"Key ends in ab12",
		);
	},
};

export const Anthropic: Story = {
	args: {
		value: {
			...connectionFieldsValueOf(null),
			preset: "ANTHROPIC",
			apiProtocol: "anthropic-messages",
			baseUrl: "https://api.anthropic.com",
			authMode: "API_KEY",
		},
	},
	play: async ({ canvas }) => {
		await expectClosedSelectShows(canvas, "API", "Messages API (Anthropic)");
		await expect(canvas.getByRole("textbox", { name: "Base URL" })).toHaveValue(
			"https://api.anthropic.com",
		);
	},
};

export const PreservesCompatibleApiChoice: Story = {
	args: { value: { ...connectionFieldsValueOf(null), apiProtocol: "openai-completions" } },
	play: async ({ canvas, args }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: "Endpoint preset" }));
		await userEvent.click(await screen.findByRole("option", { name: "Other endpoint" }));
		await expectClosedSelectShows(canvas, "API", "Chat Completions API");
		await expect(args.onChange).toHaveBeenLastCalledWith(
			expect.objectContaining({ preset: "OTHER", apiProtocol: "openai-completions" }),
		);
	},
};
