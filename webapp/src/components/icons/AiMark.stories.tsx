import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { AI_CONNECTION_PLATFORMS } from "./ai-connection-platform-logos";
import { AI_MODEL_BRANDS } from "./ai-model-brand-logos";
import { AiMark } from "./AiMark";

/**
 * The maker's mark, with the service that receives requests as a corner badge. Every catalog entry
 * renders on the same white tile, so a monochrome mark stays visible in dark mode.
 */
const meta = {
	component: AiMark,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { brand: "OPENAI", platform: "AZURE", size: "lg" },
} satisfies Meta<typeof AiMark>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelectorAll("img")).toHaveLength(2);
	},
};

/** Logos serving Qwen: the service a university or company runs itself, badged on the maker. */
export const LogosServingQwen: Story = { args: { brand: "QWEN", platform: "LOGOS" } };

/** Only one fact declared: that mark fills the tile, with no badge. */
export const ServiceOnly: Story = { args: { brand: undefined, platform: "LOGOS" } };

/** Neither declared: a neutral bot, never a guessed vendor. */
export const Undeclared: Story = {
	args: { brand: undefined, platform: undefined },
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelector("img")).toBeNull();
		await expect(canvasElement.querySelector("svg")).not.toBeNull();
	},
};

export const Catalog: Story = {
	render: () => (
		<div className="grid max-w-xl gap-4">
			<div className="flex flex-wrap gap-2">
				{AI_MODEL_BRANDS.map((brand) => (
					<AiMark key={brand} brand={brand} />
				))}
			</div>
			<div className="flex flex-wrap gap-2">
				{AI_CONNECTION_PLATFORMS.map((platform) => (
					<AiMark key={platform} platform={platform} />
				))}
			</div>
		</div>
	),
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelectorAll("img")).toHaveLength(
			AI_MODEL_BRANDS.length + AI_CONNECTION_PLATFORMS.length,
		);
	},
};

export const Dark: Story = { ...Catalog, globals: { theme: "dark" } };
