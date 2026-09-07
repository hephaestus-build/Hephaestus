import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	userSidebar: [
		{
			type: "category",
			label: "Start here",
			collapsible: false,
			items: ["overview", "getting-started", "privacy"],
		},
		{
			type: "category",
			label: "Using Hephaestus",
			collapsed: false,
			items: ["ai-code-review", "ai-mentor", "workspace", "product-feedback"],
		},
		{
			type: "category",
			label: "Optional features",
			items: ["leaderboard", "achievements"],
		},
		{ type: "doc", id: "accessibility", label: "Accessibility" },
	],
};

export default sidebars;
