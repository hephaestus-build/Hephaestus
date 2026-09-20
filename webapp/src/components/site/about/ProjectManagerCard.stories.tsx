import type { Meta, StoryObj } from "@storybook/react";

import { ProjectManagerCard } from "./ProjectManagerCard";

const meta = {
	component: ProjectManagerCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	argTypes: {
		projectManager: {
			description: "Project manager data object containing profile information",
		},
	},
} satisfies Meta<typeof ProjectManagerCard>;

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
	},
};

export const Placeholder: Story = {
	args: {
		projectManager: {
			id: 12_345,
			login: "techleader",
			name: "Alex Johnson",
			title: "Engineering manager",
			description:
				"Alex leads a software team and works on its architecture, planning, and developer experience.",
			avatarUrl: "https://i.pravatar.cc/300?img=8",
			htmlUrl: "https://github.com/techleader",
			websiteUrl: "https://alexjohnson.dev",
		},
	},
};

export const LongDescription: Story = {
	args: {
		projectManager: {
			id: 67_890,
			login: "innovator",
			name: "Dr. Sarah Chen",
			title: "Research director",
			description:
				"Sarah leads research projects in artificial intelligence, distributed systems, and human-computer interaction. She also works with engineering teams to test research ideas in software projects.",
			avatarUrl: "https://i.pravatar.cc/300?img=5",
			htmlUrl: "https://github.com/innovator",
			websiteUrl: "https://sarahchen.research.com",
		},
	},
};
