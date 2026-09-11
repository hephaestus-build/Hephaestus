import type { Meta, StoryObj } from "@storybook/react";

import { LeagueIcon } from "./LeagueIcon";

const meta = {
	component: LeagueIcon,
	tags: ["autodocs"],
	parameters: {
		layout: "centered",
	},
	args: {
		leaguePoints: 1100,
		size: "default",
		showPoints: false,
	},
	argTypes: {
		leaguePoints: {
			control: {
				type: "number",
				min: 0,
				max: 3000,
				step: 100,
			},
			description: "League points used to determine the tier",
		},
		size: {
			control: "inline-radio",
			options: ["sm", "default", "lg", "max", "full"],
			description: "Size of the league icon",
		},
	},
} satisfies Meta<typeof LeagueIcon>;

export default meta;
type Story = StoryObj<typeof LeagueIcon>;

export const NotRanked: Story = {
	args: {
		leaguePoints: undefined,
	},
};

export const Bronze: Story = {
	args: {
		leaguePoints: 250,
	},
};

export const Silver: Story = {
	args: {
		leaguePoints: 1250,
	},
};

export const Gold: Story = {
	args: {
		leaguePoints: 1500,
	},
};

export const Diamond: Story = {
	args: {
		leaguePoints: 1750,
	},
};

export const Master: Story = {
	args: {
		leaguePoints: 2250,
	},
};

export const WithPoints: Story = {
	args: {
		leaguePoints: 1500,
		showPoints: true,
	},
};

export const SmallSize: Story = {
	args: {
		leaguePoints: 1500,
		size: "sm",
	},
};

export const LargeSize: Story = {
	args: {
		leaguePoints: 1500,
		size: "lg",
	},
};

export const AllLeagueTiers: Story = {
	argTypes: { leaguePoints: { control: false } },
	render: (args) => (
		<div className="flex gap-6 items-end">
			<div className="flex flex-col items-center">
				<LeagueIcon {...args} {...NotRanked.args} />
				<span className="mt-2 text-xs text-muted-foreground">Not Ranked</span>
			</div>
			<div className="flex flex-col items-center">
				<LeagueIcon {...args} {...Bronze.args} />
				<span className="mt-2 text-xs text-muted-foreground">Bronze</span>
			</div>
			<div className="flex flex-col items-center">
				<LeagueIcon {...args} {...Silver.args} />
				<span className="mt-2 text-xs text-muted-foreground">Silver</span>
			</div>
			<div className="flex flex-col items-center">
				<LeagueIcon {...args} {...Gold.args} />
				<span className="mt-2 text-xs text-muted-foreground">Gold</span>
			</div>
			<div className="flex flex-col items-center">
				<LeagueIcon {...args} {...Diamond.args} />
				<span className="mt-2 text-xs text-muted-foreground">Diamond</span>
			</div>
			<div className="flex flex-col items-center">
				<LeagueIcon {...args} {...Master.args} />
				<span className="mt-2 text-xs text-muted-foreground">Master</span>
			</div>
		</div>
	),
};
