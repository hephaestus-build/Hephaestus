import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";

import { SigningInPage } from "./SigningInPage";

/** The return trip from a provider, until the session check sends the reader on. */
const meta = {
	component: SigningInPage,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { returnTo: "/w/aet/practice-profile" },
} satisfies Meta<typeof SigningInPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Signing you in…")).toBeVisible();
		// The words carry the status, so the spinner beside them stays out of the accessibility tree.
		await expect(canvas.queryByRole("status")).not.toBeInTheDocument();
		// A fast return never shows the way back; a slow one shows it after a second.
		await expect(canvas.queryByRole("link", { name: "Back to sign in" })).not.toBeInTheDocument();
		const back = await canvas.findByRole("link", { name: "Back to sign in" }, { timeout: 3000 });
		await expect(back).toHaveAttribute("href", "/login?returnTo=/w/aet/practice-profile");
	},
};

export const Dark: Story = { globals: { theme: "dark" } };

export const OnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
