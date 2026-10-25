import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { SigningInPage } from "./SigningInPage";

/** The return trip from a provider, until the session check sends the reader on. */
const meta = {
	component: SigningInPage,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { returnTo: "/w/aet/practice-profile", showBackLink: false },
} satisfies Meta<typeof SigningInPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A fast return: the status alone, with no way back that competes with it. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Signing you in…")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: "Back to sign in" })).not.toBeInTheDocument();
	},
};

/** The check is slow, so the reader gets a way back to the sign-in page. */
export const Slow: Story = {
	args: { showBackLink: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Signing you in…")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Back to sign in" })).toHaveAttribute(
			"href",
			"/login?returnTo=/w/aet/practice-profile",
		);
	},
};

export const Dark: Story = { args: { showBackLink: true }, globals: { theme: "dark" } };

export const OnAPhone: Story = {
	args: { showBackLink: true },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
