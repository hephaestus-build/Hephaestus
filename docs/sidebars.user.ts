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
				"ai-code-review",
				"practice-profile",
				"practices-across-the-workspace",
				"activity",
				"public-activity",
				"ask-hephaestus",
				"user-settings",
				"passkeys",
				"browser-extension",
				"browser-extension-privacy",
				"product-feedback",
			],
		},
		{
			type: "category",
			label: "For workspace admins",
			collapsed: false,
			items: [
				"workspace",
				"integrations",
				"practices",
				"practice-visual-rules",
				"practice-reviews",
				"scans",
				"ai-models",
				"publish-public-activity",
			],
		},
		{ type: "doc", id: "accessibility", label: "Accessibility" },
	],
};

export default sidebars;
