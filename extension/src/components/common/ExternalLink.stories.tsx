import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ExternalLink } from "~/components/common/ExternalLink";

const meta = {
	component: ExternalLink,
	tags: ["autodocs"],
	args: {
		href: "https://heph.example.test/w/team/reviews/scm.issue/7",
		allowedOrigin: "https://heph.example.test",
		children: "Open in Hephaestus",
	},
} satisfies Meta<typeof ExternalLink>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const link = canvas.getByRole("link", { name: /Open in Hephaestus/u });
		await expect(link).toHaveAttribute("target", "_blank");
		await expect(link).toHaveAttribute("rel", "noreferrer noopener");
	},
};

export const OtherOrigin: Story = {
	args: { href: "https://evil.example/phish" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
		await expect(canvas.getByText("Open in Hephaestus")).toBeVisible();
	},
};

export const ScriptUrl: Story = {
	// oxlint-disable-next-line no-script-url -- The story proves such an address never becomes a link.
	args: { href: "javascript:alert(1)" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link")).toBeNull();
	},
};
