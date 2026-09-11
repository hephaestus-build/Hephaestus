import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Label } from "@/components/ui/label";
import { expectGenuinelyDisabled } from "@/test/controls";

import { ModelPicker } from "./ModelPicker";
import { mockAvailableModels } from "./story-mock-data";

/**
 * A model's declared data handling travels with its name: the tier line under each option and the
 * icon beside it are the same registry entry the tables and binding rows show, so an admin choosing
 * a model for a tier row never has to remember what it was declared as. The `tier` prop filters
 * rather than disables the other models: a disabled option would still have to be read past, and
 * the row already says which tier it holds.
 */
const meta = {
	component: ModelPicker,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		id: "review-model",
		"aria-labelledby": "review-model-label",
		availableModels: mockAvailableModels,
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
		await expect(
			await screen.findByRole("option", { name: /GPT-5 .* Provider, nothing kept/ }),
		).toBeVisible();
		await expect(
			await screen.findByRole("option", { name: /My OpenAI key .* Not declared/ }),
		).toBeVisible();
	},
};

export const FilteredToTier: Story = {
	args: { tier: "IN_HOUSE" },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("combobox"));
		await expect(
			await screen.findByRole("option", { name: /Local Llama .* Stays in-house/ }),
		).toBeVisible();
		await expect(screen.getAllByRole("option")).toHaveLength(1);
	},
};

/**
 * With nothing to list the picker disables itself; the row around it says what an empty list means
 * for that tier, in the shape shown here, because only the caller knows whether the reader can add
 * a model.
 */
export const NoModelsForTier: Story = {
	args: { tier: "PROVIDER_KEPT", "aria-describedby": "model-picker-empty" },
	render: (args) => (
		<div className="space-y-2">
			<ModelPicker {...args} />
			<p id="model-picker-empty" className="text-sm text-muted-foreground">
				No model declared as <span className="font-medium">Provider, kept for safety checks</span>{" "}
				is available here yet. Ask your host, or add one under your own providers.
			</p>
		</div>
	),
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("combobox"));
		await expect(screen.queryByRole("option")).not.toBeInTheDocument();
	},
};
