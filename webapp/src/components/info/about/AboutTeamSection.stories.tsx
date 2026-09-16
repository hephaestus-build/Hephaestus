import type { Meta, StoryObj } from "@storybook/react";

import { AboutTeamSection } from "./AboutTeamSection";

const meta = {
	component: AboutTeamSection,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
} satisfies Meta<typeof AboutTeamSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		projectManager: {
			id: 5_898_705,
			login: "felixtjdietrich",
			name: "Felix T.J. Dietrich",
			title: "Project lead",
			description:
				"Felix started Hephaestus as part of his doctoral research at TUM and leads the open-source project. His research studies how feedback on day-to-day software work can support developer learning.",
			avatarUrl: "https://avatars.githubusercontent.com/u/5898705",
			htmlUrl: "https://github.com/felixtjdietrich",
			websiteUrl: "https://aet.cit.tum.de/people/dietrich/",
		},
		contributors: [
			{
				id: 12_345_678,
				name: "Alice Developer",
				login: "contributor1",
				avatarUrl: "https://avatars.githubusercontent.com/u/12345678",
				htmlUrl: "https://github.com/contributor1",
			},
			{
				id: 87_654_321,
				name: "Bob Builder",
				login: "contributor2",
				avatarUrl: "https://avatars.githubusercontent.com/u/87654321",
				htmlUrl: "https://github.com/contributor2",
			},
		],
		isContributorsLoading: false,
		isContributorsError: false,
	},
};

export const IsLoading: Story = {
	args: {
		...Default.args,
		isContributorsLoading: true,
	},
};

export const IsError: Story = {
	args: {
		...Default.args,
		isContributorsError: true,
	},
};

export const NoContributors: Story = {
	args: {
		...Default.args,
		contributors: [],
	},
};
