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
			items: [
				"practice-profile",
				"activity",
				"ai-code-review",
				"ai-mentor",
				"workspace",
				"user-settings",
				"browser-extension",
				"browser-extension-privacy",
				"product-feedback",
			],
		},
		{ type: "doc", id: "accessibility", label: "Accessibility" },
	],
};

export default sidebars;
