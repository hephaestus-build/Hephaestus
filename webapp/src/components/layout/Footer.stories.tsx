import type { Meta, StoryObj } from "@storybook/react";

import Footer from "./Footer";

const meta = {
	component: Footer,
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "desktop" },
	},
	tags: ["autodocs"],
} satisfies Meta<typeof Footer>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		buildInfo: undefined,
	},
};

/** A deployment other than production names the commit it runs. */
export const Preview: Story = {
	args: {
		buildInfo: { commit: "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0" },
	},
};

/** Production names no commit. */
export const Production: Story = {
	args: {
		isProduction: true,
		buildInfo: { commit: "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0" },
	},
};

export const Mobile: Story = {
	args: {
		buildInfo: { commit: "9876543" },
	},
	globals: {
		viewport: {
			value: "mobile1",
			isRotated: false,
		},
	},
};
