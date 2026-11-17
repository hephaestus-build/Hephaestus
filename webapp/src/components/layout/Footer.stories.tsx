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

export const Preview: Story = {
	args: {
		buildInfo: {
			branch: "feat/new-footer-design",
			commit: "a1b2c3d4e5f6g7h8i9j0",
			deployedAt: "2024-12-14T17:44:00Z",
		},
	},
};

export const PreviewNoTime: Story = {
	args: {
		buildInfo: {
			branch: "fix/mobile-layout",
			commit: "9876543",
		},
	},
};

export const Mobile: Story = {
	args: {
		buildInfo: {
			branch: "fix/mobile-layout",
			commit: "9876543",
			deployedAt: "2024-12-14T10:00:00Z",
		},
	},
	globals: {
		viewport: {
			value: "mobile1",
			isRotated: false,
		},
	},
};
