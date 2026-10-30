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
			label: "Start",
			collapsed: false,
			items: [
				"local-development",
				"local-verification",
				{
					type: "category",
					label: "Testing",
					link: { type: "doc", id: "testing" },
					items: [
						"e2e-testing",
						"security-mutation-testing",
						"sandbox-footprint",
						"capacity-benchmark",
						{
							type: "category",
							label: "Accessibility audit",
							link: { type: "doc", id: "accessibility-audit-plan" },
							items: [
								"accessibility-audit-record-template",
								"accessibility-audit-record-2026-10-03",
							],
						},
					],
				},
				"ai-agent-workflow",
			],
		},
		{
			type: "category",
			label: "Conventions",
			items: [
				"coding-guidelines",
				"simplified-technical-english",
				"documentation-guide",
				"check-a-docs-page",
				"add-a-user-guide-screenshot",
				"structured-logging",
				"api-error-handling",
				"brand-assets",
				"onboarding-guidelines",
			],
		},
		{
			type: "category",
			label: "Architecture",
			items: [
				"system-design",
				"workspace-context",
				"sync-lifecycle",
				"database-schema",
				"database-migration",
				"activity-people-aggregate",
				"instance-admin",
				"product-feedback",
				"browser-extension",
			],
		},
		{
			type: "category",
			label: "Practices and feedback",
			items: [
				"practice-review-pipeline",
				"practice-review-runtime",
				"item-source-contract",
				"practice-catalog",
				"practice-visuals",
				"practice-script-runner",
				"practice-feedback-schema",
				"practice-feedback-language",
				"practice-review-glossary",
				"evaluation-provenance",
				"feedback-quality-evals",
				"label-feedback-quality",
			],
		},
		{
			type: "category",
			label: "Agent runtime",
			items: ["unified-pi-runtime", "agent/agent-workspace-abi", "llm-cost-vocabulary"],
		},
		{
			type: "category",
			label: "CI and releases",
			items: [
				"ci-cd",
				"supply-chain-security",
				"add-a-ci-service",
				"recover-the-chromatic-baseline",
				"release-management",
				"delivery-metrics",
				"vulnerability-remediation",
			],
		},
	],
};

export default sidebars;
