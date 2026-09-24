import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ARTIFACT_KIND_VALUES } from "@/lib/artifact-kinds";

import { WorkTypeLabel } from "./WorkTypeLabel";

const meta = {
	component: WorkTypeLabel,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { artifactKind: "scm.pull_request" },
} satisfies Meta<typeof WorkTypeLabel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const EveryKind: Story = {
	render: () => (
		<ul className="space-y-2 text-sm">
			{ARTIFACT_KIND_VALUES.map((kind) => (
				<li key={kind}>
					<WorkTypeLabel artifactKind={kind} />
				</li>
			))}
		</ul>
	),
	play: async ({ canvas }) => {
		// Every kind gets its own wording. A missing case falls through to the neutral label, which
		// reads fine on its own and is only wrong because another kind already says it.
		const labels = canvas.getAllByRole("listitem").map((item) => item.textContent.trim());
		await expect(new Set(labels).size).toBe(ARTIFACT_KIND_VALUES.length);
	},
};

/** A kind this build has never heard of takes the neutral glyph rather than borrowing another's. */
export const UnknownKind: Story = {
	args: { artifactKind: "scm.something_new" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("scm.something_new")).toBeVisible();
	},
};

export const Missing: Story = {
	args: { artifactKind: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Reviewed work")).toBeVisible();
	},
};
