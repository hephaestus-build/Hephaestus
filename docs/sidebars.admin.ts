import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	adminSidebar: [
		{ type: "doc", id: "overview", label: "Overview" },
		{
			type: "category",
			label: "Deploy",
			collapsed: false,
			items: [
				{ type: "doc", id: "install", label: "Docker Compose on one host" },
				"pull-based-deployment",
				"runtime-roles",
				"reference-deployment",
			],
		},
		{
			type: "category",
			label: "Configure",
			collapsed: false,
			items: [
				"configuration-readiness",
				"production-setup",
				"integrations",
				"github-integration",
				"ai-providers",
				"passkeys",
				"browser-extension",
				"workspace-subdomains",
				"public-activity",
				{
					type: "category",
					label: "Practices and feedback",
					items: [
						"practice-catalog",
						"writing-practices",
						"practice-scripts",
						"practice-review",
						"practice-scans",
						"product-feedback",
					],
				},
			],
		},
		{
			type: "category",
			label: "Operate",
			collapsed: false,
			items: [
				{
					type: "category",
					label: "Upgrade",
					link: { type: "doc", id: "upgrade" },
					items: [
						"liquibase-baseline-runbook",
						"native-hibernate-json-cutover",
						"practice-model-cutover",
					],
				},
				"backup-restore",
				"troubleshooting",
				"sizing",
				"production-operations-runbook",
				"observability",
				"instance-admin",
				"practice-review-operations",
				"webhook-ingestion-operations",
				"activity-history-repair",
			],
		},
		{
			type: "category",
			label: "Security",
			collapsed: false,
			items: [
				"threat-model",
				"credential-key-rotation",
				"release-image-lock",
				"legal-pages",
				{
					type: "category",
					label: "Data-Protection Documentation",
					link: { type: "doc", id: "dsms/dsms" },
					items: [
						{
							type: "doc",
							id: "dsms/record-of-processing",
							label: "Record of Processing (Art. 30)",
						},
						{ type: "doc", id: "dsms/dpia-prescreen", label: "DPIA Pre-Screen (Art. 35)" },
						{ type: "doc", id: "dsms/processor-checklist", label: "Processor Checklist (Art. 28)" },
						{
							type: "doc",
							id: "dsms/item-source-governance",
							label: "Artifact-Source Governance",
						},
						{ type: "doc", id: "dsms/personal-data-map", label: "Personal-Data Map" },
						{
							type: "doc",
							id: "dsms/tum-privacy-notice-changes",
							label: "TUM Privacy Notice Changes",
						},
					],
				},
			],
		},
		{
			type: "category",
			label: "Policies",
			collapsed: false,
			items: [
				"compatibility-policy",
				{
					type: "link",
					label: "Security policy",
					href: "https://github.com/hephaestus-build/Hephaestus/blob/main/SECURITY.md",
				},
			],
		},
	],
};

export default sidebars;
