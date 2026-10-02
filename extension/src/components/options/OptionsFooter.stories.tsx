import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { OptionsFooter } from "~/components/options/OptionsFooter";

const meta = {
	component: OptionsFooter,
	tags: ["autodocs"],
	args: {
		helpUrl: "https://docs.hephaestus.build/user/browser-extension",
		privacyUrl: "https://docs.hephaestus.build/user/browser-extension-privacy",
		docsOrigin: "https://docs.hephaestus.build",
		version: "0.80.0",
	},
} satisfies Meta<typeof OptionsFooter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: /Privacy/u })).toHaveAttribute(
			"href",
			"https://docs.hephaestus.build/user/browser-extension-privacy",
		);
		await expect(canvas.getByRole("link", { name: /Help/u })).toHaveAttribute("target", "_blank");
		await expect(canvas.getByText("Hephaestus for Chrome 0.80.0")).toBeVisible();
	},
};

/** A link that points anywhere but the documentation renders as text, never as a live link. */
export const ForeignLinkIsInert: Story = {
	args: { helpUrl: "https://evil.example/help", version: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: /Help/u })).toBeNull();
		await expect(canvas.getByText("Help")).toBeVisible();
	},
};
