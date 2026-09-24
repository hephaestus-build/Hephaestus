import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";
import { HeroScene, LandingHeroSection } from "./LandingHeroSection";

const meta = {
	component: LandingHeroSection,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: {
		onSignIn: fn(),
	},
} satisfies Meta<typeof LandingHeroSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// One DOM serves the scattered and the stacked composition; a second copy means the
		// two layouts have drifted apart.
		await expect(canvas.getAllByText("Export reports to CSV")).toHaveLength(1);
	},
};

export const Mobile: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
};

export const Tablet: Story = {
	parameters: {
		viewport: { defaultViewport: "tablet" },
		chromatic: { viewports: [768] },
	},
};

export const DarkMode: Story = {
	globals: { theme: "dark" },
};

export const ReadmeExport: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
	},
	render: (args) => (
		<div data-readme-export="landing-hero" className="mx-auto w-full max-w-[1280px] bg-background">
			<LandingHeroSection {...args} />
		</div>
	),
};

export const SceneExport: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
	},
	render: () => (
		<div data-readme-export="feedback-scene" className="w-[56rem] bg-background p-6">
			<HeroScene />
		</div>
	),
};
