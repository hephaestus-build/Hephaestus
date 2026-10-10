import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	adminSidebar: [
		{ type: "doc", id: "overview", label: "Overview" },
		{
			type: "category",
			label: "Install",
			collapsed: false,
			items: [
				"install",
				"configuration-readiness",
				"production-setup",
				"compatibility-policy",
				"browser-extension",
			],
		},
		{
			type: "category",
			label: "Integrations",
			collapsed: false,
			items: ["integrations", "github-integration"],
		},
		{
			type: "category",
			label: "Practices and feedback",
			collapsed: false,
			items: [
				"practice-catalog",
				"writing-practices",
				"ai-providers",
				"practice-review",
				"practice-review-operations",
				"product-feedback",
			],
		},
		{
			type: "category",
			label: "Operate",
			collapsed: false,
			items: [
				"instance-admin",
				"runtime-roles",
				"workspace-subdomains",
				"production-operations-runbook",
				"activity-history-repair",
				"observability",
				"webhook-ingestion-operations",
				"backup-restore",
				"liquibase-baseline-runbook",
				"release-image-lock",
				"pull-based-deployment",
			],
		},
		{
			type: "category",
			label: "Security and compliance",
			collapsed: false,
			items: [
				"credential-key-rotation",
				"threat-model",
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
							id: "dsms/artifact-source-governance",
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
	],
};

export default sidebars;
