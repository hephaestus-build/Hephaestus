import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { ProfileHeader } from "./ProfileHeader";

const meta = {
	component: ProfileHeader,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "example-workspace",
	},
} satisfies Meta<typeof ProfileHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		isLoading: false,
		user: {
			id: 1,
			login: "johndoe",
			name: "John Doe",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/johndoe",
		},
		userXpRecord: {
			currentLevel: 5,
			currentLevelXP: 450,
			xpNeeded: 1000,
			totalXP: 5450,
		},
		leaguePoints: 1450,
		firstContribution: new Date("2022-05-15T00:00:00Z"),
		contributedRepositories: [
			{
				id: 1,
				name: "Hephaestus",
				nameWithOwner: "ls1intum/Hephaestus",
				description: "A GitHub contribution tracking tool",
				htmlUrl: "https://github.com/ls1intum/Hephaestus",
				hiddenFromContributions: false,
			},
			{
				id: 2,
				name: "Artemis",
				nameWithOwner: "ls1intum/Artemis",
				description: "Interactive learning platform",
				htmlUrl: "https://github.com/ls1intum/Artemis",
				hiddenFromContributions: false,
			},
		],
	},
};

export const Loading: Story = {
	args: {
		isLoading: true,
		leaguePoints: 0,
	},
};

export const NoRepositories: Story = {
	args: {
		isLoading: false,
		user: {
			id: 1,
			login: "janedoe",
			name: "Jane Doe",
			avatarUrl: "https://github.com/octocat.png",
			htmlUrl: "https://github.com/janedoe",
		},
		leaguePoints: 750,
		firstContribution: new Date("2023-01-10T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const BronzeLeague: Story = {
	args: {
		isLoading: false,
		user: {
			id: 2,
			login: "bronzeUser",
			name: "Bronze User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/bronzeUser",
		},
		leaguePoints: 1000,
		firstContribution: new Date("2023-03-15T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const SilverLeague: Story = {
	args: {
		isLoading: false,
		user: {
			id: 3,
			login: "silverUser",
			name: "Silver User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/silverUser",
		},
		leaguePoints: 1400,
		firstContribution: new Date("2022-10-10T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const GoldLeague: Story = {
	args: {
		isLoading: false,
		user: {
			id: 4,
			login: "goldUser",
			name: "Gold User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/goldUser",
		},
		leaguePoints: 1650,
		firstContribution: new Date("2022-07-22T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const DiamondLeague: Story = {
	args: {
		isLoading: false,
		user: {
			id: 5,
			login: "diamondUser",
			name: "Diamond User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/diamondUser",
		},
		leaguePoints: 1900,
		firstContribution: new Date("2021-12-05T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const MasterLeague: Story = {
	args: {
		isLoading: false,
		user: {
			id: 6,
			login: "masterUser",
			name: "Master User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/masterUser",
		},
		leaguePoints: 2200,
		firstContribution: new Date("2020-05-01T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const HighLevelUser: Story = {
	args: {
		isLoading: false,
		user: {
			id: 7,
			login: "highLevel",
			name: "High Level User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/highLevel",
		},
		userXpRecord: {
			currentLevel: 50,
			currentLevelXP: 25000,
			xpNeeded: 50000,
			totalXP: 1250000,
		},
		leaguePoints: 3000,
		firstContribution: new Date("2019-01-01T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const LevelUpReady: Story = {
	args: {
		isLoading: false,
		user: {
			id: 8,
			login: "levelUp",
			name: "Level Up User",
			avatarUrl: "https://github.com/github.png",
			htmlUrl: "https://github.com/levelUp",
		},
		userXpRecord: {
			currentLevel: 9,
			currentLevelXP: 990,
			xpNeeded: 1000,
			totalXP: 9990,
		},
		leaguePoints: 2000,
		firstContribution: new Date("2020-01-01T00:00:00Z"),
		contributedRepositories: [],
	},
};

export const Mobile: Story = {
	args: Default.args,
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		// The league is this person's standing, so it belongs beside them at every width. Stacked, it
		// became a full-width centred band below the identity and read as a section of its own.
		const league = await canvas.findByLabelText(/tier$/);
		const name = canvas.getByRole("heading", { level: 1 });
		const leagueBox = league.getBoundingClientRect();
		const nameBox = name.getBoundingClientRect();
		// Same band: the two boxes overlap vertically, which "league.top < name.bottom" alone would
		// also allow if the league sat entirely above the heading.
		await expect(Math.min(leagueBox.bottom, nameBox.bottom)).toBeGreaterThan(
			Math.max(leagueBox.top, nameBox.top),
		);
		// Beside it rather than over it: the league starts where the heading has ended.
		await expect(leagueBox.left).toBeGreaterThanOrEqual(nameBox.right);
	},
};
