import type { Meta, StoryObj } from "@storybook/react";
import { PracticeNextStepCallout } from "./PracticeNextStepCallout";

const meta = {
	component: PracticeNextStepCallout,
	parameters: {
		layout: "padded",
		docs: {
			description: {
				component:
					"The one thing to try next, shown the same way whether it applies to a whole group or to a " +
					"single practice. It takes its body as children, so the caller decides what a next step is " +
					"— delivered guidance, or the catalog's own description of what good looks like.",
			},
		},
	},
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="max-w-lg">
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof PracticeNextStepCallout>;

export default meta;
type Story = StoryObj<typeof meta>;
export const SuggestedNextStep: Story = {
	args: {
		label: "Suggested next step",
		children:
			"Keep changes focused on one concern, so a reviewer can hold the whole change in view.",
	},
};
export const InsideAPracticeRow: Story = {
	args: {
		label: "Your next step",
		className: "rounded-none border-x-0 border-b-0",
		children:
			"Record why the timeout was raised next to the value itself — the diff cannot say it for you.",
	},
	decorators: [
		(Story) => (
			<div className="max-w-lg overflow-hidden rounded-xl border bg-card">
				<div className="p-4 text-sm font-medium">Explain significant decisions</div>
				<Story />
			</div>
		),
	],
};
export const LongGuidance: Story = {
	args: {
		label: "Your next step",
		children:
			"Split this into the refactor and the behaviour change, land the refactor first, and say in " +
			"the second description which behaviour moved — otherwise a reviewer has to separate the two " +
			"themselves before they can judge either.",
	},
};
