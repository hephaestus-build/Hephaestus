import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { AGENT_PURPOSES } from "./agent-purpose-defs";
import { ModelKindMark } from "./ModelKindMark";

/**
 * Which kind of model a row, cell or need is about. The glyph carries the kind's hue and the tile
 * stays neutral: a tinted ground is a practice group's pill, and a tinted badge is a status, so
 * neither may stand for a kind.
 *
 * The three precompute kinds take violet, cyan and fuchsia, each at least 30° (OKLCH) from the
 * status tones and the mentor accent. Practice reviews and Heph stay neutral, because this mark
 * does not introduce Heph.
 */
const meta = {
	component: ModelKindMark,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { purpose: "PRACTICE_DECISION", size: "sm", label: "visible" },
} satisfies Meta<typeof ModelKindMark>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Decision model")).toBeVisible();
		await expect(canvas.getByText("Decision model").getBoundingClientRect().width).toBeGreaterThan(
			1,
		);
	},
};

/** Every kind at both sizes: inline beside text, and leading a list row. */
export const EveryKind: Story = {
	render: (args) => (
		<div className="grid gap-6 sm:grid-cols-2">
			{(["sm", "md"] as const).map((size) => (
				<ul key={size} aria-label={`Size ${size}`} className="grid gap-3 text-sm">
					{AGENT_PURPOSES.map((purpose) => (
						<li key={purpose}>
							<ModelKindMark {...args} purpose={purpose} size={size} />
						</li>
					))}
				</ul>
			))}
		</div>
	),
	play: async ({ canvas }) => {
		for (const size of ["sm", "md"]) {
			const list = canvas.getByRole("list", { name: `Size ${size}` });
			await expect(list).toHaveTextContent(
				"Practice reviewsHephDecision modelEmbedding modelReranking model",
			);
		}
	},
};

/** In a cell or beside a heading that already names the kind, the name is for a screen reader. */
export const NameForScreenReaders: Story = {
	args: { purpose: "PRACTICE_EMBEDDING", label: "sr-only" },
	play: async ({ canvas }) => {
		// Present in the accessibility tree, but drawn in a 1px clip: only the tile shows.
		const name = canvas.getByText("Embedding model");
		await expect(name.getBoundingClientRect().width).toBeLessThanOrEqual(1);
		await expect(canvas.getByText("Embedding model").closest("[aria-hidden]")).toBeNull();
	},
};

export const Dark: Story = {
	...EveryKind,
	globals: { theme: "dark" },
};
