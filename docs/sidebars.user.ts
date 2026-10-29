import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	userSidebar: [
		{
			type: "category",
			label: "Start here",
			collapsible: false,
			link: { type: "doc", id: "overview" },
			items: ["getting-started", "privacy"],
		},
		{
			type: "category",
			label: "Using Hephaestus",
			collapsed: false,
			items: [
				"practice-profile",
				"activity",
				"public-activity",
				"ai-code-review",
				"ask-hephaestus",
				"workspace",
				"user-settings",
				"passkeys",
				"browser-extension",
				"browser-extension-privacy",
				"product-feedback",
			],
		},
		{ type: "doc", id: "accessibility", label: "Accessibility" },
	],
};

export default sidebars;
