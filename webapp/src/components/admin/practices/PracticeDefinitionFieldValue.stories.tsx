import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, waitFor } from "storybook/test";

import { definitionOf, mockScan } from "@/components/admin/practice-editor/fixtures";

import { practiceScript } from "./fixtures";
import { PracticeDefinitionFieldValue } from "./PracticeDefinitionFieldValue";

const meta = {
	component: PracticeDefinitionFieldValue,
	tags: ["autodocs"],
	args: { definition: definitionOf(), field: "CRITERIA" },
} satisfies Meta<typeof PracticeDefinitionFieldValue>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Criteria: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/small enough to review in one sitting/u)).toBeVisible();
	},
};

export const BuiltInScript: Story = {
	args: { field: "SCRIPT" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Built-in")).toBeVisible();
	},
};

/**
 * A practice script is the one field that is code, so it is numbered by line and can be copied.
 * Every other field is prose and keeps the reading face.
 */
export const Script: Story = {
	args: { field: "SCRIPT", definition: definitionOf({ script: practiceScript() }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Copy practice script" })).toBeEnabled();
		await waitFor(async () => {
			// One line per element: the import names it, and so does the call.
			await expect(canvas.getAllByText(/definePracticeScript/u)[0]).toBeVisible();
		});
	},
};

export const ReviewedByPeople: Story = {
	args: {
		field: "REVIEWED_BY",
		definition: definitionOf({
			reviewedBy: {
				by: "PEOPLE",
				humanReason: { code: "DESIGN_JUDGEMENT", description: "Design choices need a person." },
				limits: [],
			},
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/People\s+Design choices need a person\./u)).toBeVisible();
	},
};

export const Scan: Story = {
	args: { field: "SCAN", definition: definitionOf({ scan: mockScan }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Include: \*\*\/\*\.ts, \*\*\/\*\.tsx/u)).toBeVisible();
	},
};
