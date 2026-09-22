import type { Meta, StoryObj } from "@storybook/react";

import { ContributorGrid } from "./ContributorGrid";

/**
 * ContributorGrid component for displaying a grid of contributors
 * with customizable layout and loading states.
 */
const meta = {
	component: ContributorGrid,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	argTypes: {
		size: {
			control: "select",
			options: ["sm", "md"],
			description: "Size of contributor cards",
		},
		layout: {
			control: "select",
			options: ["compact", "comfortable"],
			description: "Grid density and spacing",
		},
		isLoading: {
			control: "boolean",
			description: "Loading state",
		},
		loadingSkeletonCount: {
			control: "number",
			description: "Number of skeleton items to show when loading",
		},
	},
} satisfies Meta<typeof ContributorGrid>;

export default meta;
type Story = StoryObj<typeof meta>;

const mockContributors = [
	{
		id: 1,
		login: "johndoe",
		name: "John Doe",
		avatarUrl: "https://avatars.githubusercontent.com/u/1234567?v=4",
		htmlUrl: "https://github.com/johndoe",
	},
	{
		id: 2,
		login: "janedoe",
		name: "Jane Doe",
		avatarUrl: "https://avatars.githubusercontent.com/u/2345678?v=4",
		htmlUrl: "https://github.com/janedoe",
	},
	{
		id: 3,
		login: "very-long-username-example",
		name: "Alexandra Konstantinoupolis-Rodriguez",
		avatarUrl: "https://avatars.githubusercontent.com/u/3456789?v=4",
		htmlUrl: "https://github.com/very-long-username-example",
	},
	{
		id: 4,
		login: "alicejohnson",
		name: "Alice Johnson",
		avatarUrl: "https://avatars.githubusercontent.com/u/4567890?v=4",
		htmlUrl: "https://github.com/alicejohnson",
	},
	{
		id: 5,
		login: "maximilian-longername",
		name: "Dr. Maximilian Friedrich von Hohenberg-Schönberg",
		avatarUrl: "https://avatars.githubusercontent.com/u/5678901?v=4",
		htmlUrl: "https://github.com/maximilian-longername",
	},
	{
		id: 6,
		login: "charlielee",
		name: "Charlie Lee",
		avatarUrl: "https://avatars.githubusercontent.com/u/6789012?v=4",
		htmlUrl: "https://github.com/charlielee",
	},
];

/**
 * Grid with multiple contributors in default configuration.
 */
export const Default: Story = {
	args: {
		contributors: mockContributors,
	},
};

/**
 * Grid in loading state with skeleton placeholders.
 */
export const Loading: Story = {
	args: {
		contributors: [],
		isLoading: true,
		loadingSkeletonCount: 6,
	},
};

/**
 * Compact grid with small contributor cards.
 */
export const Compact: Story = {
	args: {
		contributors: mockContributors,
		size: "sm",
		layout: "compact",
	},
};

/**
 * Comfortable grid layout with balanced spacing.
 */
export const Comfortable: Story = {
	args: {
		contributors: mockContributors,
		layout: "comfortable",
	},
};

/**
 * Compact grid with mixed name lengths to test text handling.
 */
export const WithLongNames: Story = {
	args: {
		contributors: mockContributors,
		size: "sm",
		layout: "compact",
	},
};
