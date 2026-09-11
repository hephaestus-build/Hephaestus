import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	contributorSidebar: [
		{
			type: "doc",
			id: "overview",
			label: "Overview",
		},
		{
			type: "category",
			label: "Development Workflow",
			items: [
				"local-development",
				"local-verification",
				"testing",
				"e2e-testing",
				"accessibility-audit-plan",
				"security-mutation-testing",
				"coding-guidelines",
				"structured-logging",
				"brand-assets",
				"api-error-handling",
				"workspace-context",
				"ai-agent-workflow",
			],
		},
		{
			type: "category",
			label: "Architecture & Data",
			items: [
				"system-design",
				"product-feedback",
				"instance-admin",
				"sync-lifecycle",
				"migration-unified-integration",
				"database-schema",
				"database-migration",
			],
		},
		{
			type: "category",
			label: "Operations",
			items: ["release-management", "ci-cd", "vulnerability-remediation"],
		},
		{
			type: "category",
			label: "Agent Runtime",
			items: ["unified-pi-runtime", "agent/agent-workspace-abi", "llm-cost-vocabulary"],
		},
		{
			type: "category",
			label: "Practices & Feedback",
			items: [
				"practice-review-pipeline",
				"practice-review-runtime",
				"artifact-source-contract",
				"practice-catalogue",
				"practice-feedback-schema",
				"practice-feedback-language",
				"practice-review-glossary",
				"evaluation-provenance",
			],
		},
	],
};

export default sidebars;
