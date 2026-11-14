import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { MODEL_READINESS_DEFS, type ModelNotReady } from "./model-readiness-defs";
import { ModelReadinessStatus } from "./ModelReadinessStatus";

/**
 * The status cell of a model row in the instance and the workspace model tables. The server decides
 * the value for both consoles; a model that can run draws plain text, not a badge.
 */
const meta = {
	component: ModelReadinessStatus,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { readiness: "READY" },
} satisfies Meta<typeof ModelReadinessStatus>;

export default meta;
type Story = StoryObj<typeof meta>;

const NOT_READY = [
	"PRICE_MISSING",
	"CONNECTION_OFF",
	"OFF",
	"LIMITS_MISSING",
	"NO_WORKSPACE_ACCESS",
] as const satisfies readonly ModelNotReady[];

export const Ready: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Ready")).toBeVisible();
	},
};

export const EveryReasonItCannotRun: Story = {
	render: () => (
		<div className="flex flex-wrap gap-2">
			{NOT_READY.map((readiness) => (
				<ModelReadinessStatus key={readiness} readiness={readiness} />
			))}
		</div>
	),
	play: async ({ canvas }) => {
		for (const readiness of NOT_READY) {
			await expect(canvas.getByText(MODEL_READINESS_DEFS[readiness].label)).toBeVisible();
		}
	},
};
