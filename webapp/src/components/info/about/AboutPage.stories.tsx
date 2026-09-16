import type { Meta, StoryObj } from "@storybook/react";

import { withStandardPage } from "@/stories/decorators";

import { AboutPage } from "./AboutPage";
import type { ProjectManager } from "./ProjectManagerCard";

const projectManager: ProjectManager = {
	id: 5_898_705,
	login: "felixtjdietrich",
	name: "Felix T.J. Dietrich",
	title: "Project lead",
	description:
		"Felix started Hephaestus as part of his doctoral research at TUM and leads the open-source project. His research studies how feedback on day-to-day software work can support developer learning.",
	avatarUrl: "https://avatars.githubusercontent.com/u/5898705",
	htmlUrl: "https://github.com/felixtjdietrich",
	websiteUrl: "https://aet.cit.tum.de/people/dietrich/",
};

const mockContributors = [
	{
		id: 1,
		login: "contributor1",
		name: "Alex Smith",
		avatarUrl: "https://i.pravatar.cc/150?img=1",
		htmlUrl: "https://github.com/contributor1",
	},
	{
		id: 2,
		login: "contributor2",
		name: "Jamie Lee",
		avatarUrl: "https://i.pravatar.cc/150?img=2",
		htmlUrl: "https://github.com/contributor2",
	},
	{
		id: 3,
		login: "contributor3",
		name: "Sam Wilson",
		avatarUrl: "https://i.pravatar.cc/150?img=3",
		htmlUrl: "https://github.com/contributor3",
	},
	{
		id: 4,
		login: "contributor4",
		name: "Taylor Kim",
		avatarUrl: "https://i.pravatar.cc/150?img=4",
		htmlUrl: "https://github.com/contributor4",
	},
	{
		id: 5,
		login: "contributor5",
		name: "Jordan Chen",
		avatarUrl: "https://i.pravatar.cc/150?img=5",
		htmlUrl: "https://github.com/contributor5",
	},
	{
		id: 6,
		login: "contributor6",
		name: "Casey Wong",
		avatarUrl: "https://i.pravatar.cc/150?img=6",
		htmlUrl: "https://github.com/contributor6",
	},
	{
		id: 7,
		login: "contributor7",
		name: "Erin Parker",
		avatarUrl: "https://i.pravatar.cc/150?img=7",
		htmlUrl: "https://github.com/contributor7",
	},
	{
		id: 8,
		login: "contributor8",
		name: "Morgan Davis",
		avatarUrl: "https://i.pravatar.cc/150?img=8",
		htmlUrl: "https://github.com/contributor8",
	},
];

const meta = {
	component: AboutPage,
	tags: ["autodocs"],
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
} satisfies Meta<typeof AboutPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Showcase: Story = {
	args: {
		isPending: false,
		isError: false,
		otherContributors: mockContributors,
		projectManager,
	},
};

export const IsLoading: Story = {
	args: {
		isPending: true,
		isError: false,
		otherContributors: [],
		projectManager,
	},
};

export const IsError: Story = {
	args: {
		isPending: false,
		isError: true,
		otherContributors: [],
		projectManager,
	},
};

export const NoContributors: Story = {
	args: {
		isPending: false,
		isError: false,
		otherContributors: [],
		projectManager,
	},
};
