import type { Meta, StoryObj } from "@storybook/react";

import { ContributorCard } from "./ContributorCard";

/**
 * ContributorCard component for displaying individual contributor information
 * with avatar, name, and GitHub profile link.
 */
const meta = {
	component: ContributorCard,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	argTypes: {
		size: {
			control: "select",
			options: ["sm", "md"],
			description: "Size of the contributor card",
		},
		className: {
			control: "text",
			description: "Additional CSS classes",
		},
	},
} satisfies Meta<typeof ContributorCard>;

export default meta;
type Story = StoryObj<typeof meta>;

const mockContributor = {
	id: 1,
	login: "johndoe",
	name: "John Doe",
	avatarUrl: "https://avatars.githubusercontent.com/u/1234567?v=4",
	htmlUrl: "https://github.com/johndoe",
};

const longNameContributor = {
	id: 2,
	login: "very-long-username-example",
	name: "Alexandra Konstantinoupolis-Rodriguez",
	avatarUrl: "https://avatars.githubusercontent.com/u/2234567?v=4",
	htmlUrl: "https://github.com/very-long-username-example",
};

/**
 * Default contributor card with medium size.
 */
export const Default: Story = {
	args: {
		contributor: mockContributor,
	},
};

/**
 * Small contributor card for compact layouts.
 */
export const Small: Story = {
	args: {
		contributor: mockContributor,
		size: "sm",
	},
};

/**
 * Medium contributor card for standard layouts.
 */
export const Medium: Story = {
	args: {
		contributor: mockContributor,
		size: "md",
	},
};

/**
 * Card with longer name and username to test text wrapping.
 */
export const LongName: Story = {
	args: {
		contributor: longNameContributor,
		size: "md",
	},
};

/**
 * Small card with long name to test compact layout handling.
 */
export const LongNameSmall: Story = {
	args: {
		contributor: longNameContributor,
		size: "sm",
	},
};
