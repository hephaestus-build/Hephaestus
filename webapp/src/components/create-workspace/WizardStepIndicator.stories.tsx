import type { Meta, StoryObj } from "@storybook/react";

import { WizardStepIndicator } from "./WizardStepIndicator";

/**
 * Visual progress indicator for the workspace creation wizard.
 * Shows three steps (Connect, Select Group, Configure) with
 * completed, current, and future states.
 */
const meta = {
	component: WizardStepIndicator,
	tags: ["autodocs"],
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Three-step progress indicator with completed checkmarks, active highlight, and future muted states.",
			},
		},
	},
	argTypes: {
		currentStep: {
			control: { type: "inline-radio" },
			options: [1, 2, 3],
			description: "The currently active wizard step",
		},
	},
	decorators: [
		(Story) => (
			<div className="w-80 p-4">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof WizardStepIndicator>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Step 1 active — no completed steps.
 */
export const Step1Connect: Story = {
	args: { currentStep: 1 },
};

/**
 * Step 2 active — step 1 shows a completed checkmark.
 */
export const Step2SelectGroup: Story = {
	args: { currentStep: 2 },
};

/**
 * Step 3 active — steps 1 and 2 show completed checkmarks.
 */
export const Step3Configure: Story = {
	args: { currentStep: 3 },
};
