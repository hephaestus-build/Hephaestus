import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { AvailableLlmModel } from "@/api/types.gen";
import { Label } from "@/components/ui/label";

import { ModelPicker } from "./ModelPicker";

const mockModels: AvailableLlmModel[] = [
	{
		processingLocation: "UNCLASSIFIED",
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "OpenAI production",
		pricingMode: "PRICED",
		per1mInputUsd: 3,
		per1mOutputUsd: 15,
		supportsReasoning: true,
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 2,
		scope: "SHARED",
		displayName: "Local Llama (self-hosted)",
		connectionDisplayName: "On-prem GPU",
		pricingMode: "NO_CHARGE",
		supportsReasoning: false,
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 10,
		scope: "WORKSPACE",
		displayName: "My OpenAI key",
		connectionDisplayName: "My provider",
		pricingMode: "UNPRICED",
		supportsReasoning: true,
	},
];

const meta = {
	component: ModelPicker,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		id: "review-model",
		"aria-labelledby": "review-model-label",
		availableModels: mockModels,
		value: null,
		onChange: fn(),
	},
	decorators: [
		(Story) => (
			<div className="w-80">
				<Label id="review-model-label" htmlFor="review-model" className="mb-2">
					Review model
				</Label>
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof ModelPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const SharedSelected: Story = {
	args: { value: { scope: "SHARED", id: 1 } },
};

export const OwnProviderSelected: Story = {
	args: { value: { scope: "WORKSPACE", id: 10 } },
};

export const NoModelsYet: Story = {
	args: { availableModels: [] },
};

export const Invalid: Story = {
	args: { invalid: true, "aria-describedby": "model-picker-error" },
	render: (args) => (
		<div className="space-y-2">
			<ModelPicker {...args} />
			<p id="model-picker-error" className="text-sm text-destructive">
				Choose the model this runs on.
			</p>
		</div>
	),
};

export const OpensAndListsGroups: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox"));
		await expect(await screen.findByRole("option", { name: /GPT-5/ })).toBeVisible();
		await expect(await screen.findByRole("option", { name: /My OpenAI key/ })).toBeVisible();
	},
};
