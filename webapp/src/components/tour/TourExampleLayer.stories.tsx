import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor } from "storybook/test";

import { withProvider } from "@/stories/decorators";
import { STORY_NOW } from "@/stories/story-clock";

import { exampleProfile } from "./example-profile";
import { TourExampleLayer } from "./TourExampleLayer";

/**
 * The example that the guided tour shows in place of the reader's page, with the app's real levels
 * over it. It is always ready: it is made-up data, so it has no loading or error state. Everything
 * in it is inert, because the tour's words, not the example, are what a screen reader reads.
 */
const meta = {
	component: TourExampleLayer,
	decorators: [withProvider("GITHUB")],
	parameters: { layout: "fullscreen" },
	args: {
		view: { page: "profile" },
		example: exampleProfile(new Date(STORY_NOW)),
		pageRef: fn(),
	},
} satisfies Meta<typeof TourExampleLayer>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The example profile, out of reach of the keyboard. */
export const Profile: Story = {
	play: async () => {
		await expect(
			screen.getByRole("heading", { level: 1, name: "Practice profile", hidden: true }),
		).toBeVisible();
		await expect(
			screen.getByText("Descriptions say what changed, not why").closest("[inert]"),
		).not.toBeNull();
	},
};

/** The app's own levels, stacked over the example as the steps of Going deeper open them. */
export const LevelsOpen: Story = {
	args: {
		view: { page: "profile", levels: ["groups", "group", "practice"], practiceTab: "guide" },
	},
	play: async () => {
		await waitFor(async () =>
			expect(document.querySelectorAll("[data-detail-depth][inert]")).toHaveLength(3),
		);
		await expect(
			document.querySelector('[data-detail-depth="2"] [data-tour-slot="tab-guide"]'),
		).not.toBeNull();
	},
};

export const ReviewsOpen: Story = {
	args: { view: { page: "profile", levels: ["reviews"] } },
	play: async () => {
		await waitFor(async () =>
			expect(
				document.querySelector('[data-detail-depth="0"] [data-tour-slot="row"]'),
			).not.toBeNull(),
		);
	},
};

/** Practices across the workspace: its bars count only, so no You mark states a standing for the reader. */
export const AcrossTheWorkspace: Story = {
	args: { view: { page: "across", level: true } },
	play: async () => {
		await waitFor(async () =>
			expect(
				document.querySelector('[data-detail-depth="0"] [data-tour-slot="focus-switch"]'),
			).not.toBeNull(),
		);
		const splits = document.querySelector('[data-tour-example] [data-tour-slot="splits"]');
		await expect(splits?.textContent).not.toMatch(/\bYou\b/u);
	},
};

export const SmallScreen: Story = {
	args: { view: { page: "profile", levels: ["groups"] } },
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		await waitFor(async () =>
			expect(
				document.querySelector('[data-detail-depth="0"] [data-tour-slot="row"]'),
			).not.toBeNull(),
		);
	},
};

export const Dark: Story = {
	args: { view: { page: "profile", levels: ["groups"] } },
	globals: { theme: "dark" },
};
