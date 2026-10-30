import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	adminSidebar: [
		{ type: "doc", id: "overview", label: "Overview" },
		{
			type: "category",
			label: "Deploy",
			collapsed: false,
			items: [
				{ type: "doc", id: "kubernetes", label: "Kubernetes (Helm chart)" },
				"first-steps",
				{ type: "doc", id: "install", label: "Install Hephaestus" },
				"pull-based-deployment",
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
						"compose-to-kubernetes",
						"upgrade-to-1-0",
						"liquibase-baseline-runbook",
						"native-hibernate-json-cutover",
						"practice-model-cutover",
					],
				},
				"backup-restore",
				"troubleshooting",
				"production-operations-runbook",
				"observability",
				"instance-admin",
				"practice-review-operations",
				"webhook-ingestion-operations",
				"webhook-replay",
				"activity-history-repair",
			],
		},
		{
			type: "category",
			label: "Security and compliance",
			collapsed: false,
			items: [
				"threat-model",
				"data-retention",
				"secret-custody",
				"credential-key-rotation",
				"release-image-lock",
				"legal-pages",
				{
					type: "category",
					label: "Data-protection documentation",
					link: { type: "doc", id: "dsms/dsms" },
					items: [
						{
							type: "doc",
							id: "dsms/record-of-processing",
							label: "Record of processing (Art. 30)",
						},
						{ type: "doc", id: "dsms/processor-checklist", label: "Processor checklist (Art. 28)" },
						{
							type: "doc",
							id: "dsms/item-source-governance",
							label: "Artifact-source governance",
						},
						{ type: "doc", id: "dsms/personal-data-map", label: "Personal-data map" },
					],
				},
				{
					type: "link",
					label: "Security policy",
					href: "https://github.com/hephaestus-build/Hephaestus/blob/main/SECURITY.md",
				},
			],
		},
		{
			type: "category",
			label: "Reference",
			collapsed: false,
			items: [
				"configuration-reference",
				"runtime-roles",
				"network-egress",
				"sizing",
				"compatibility-policy",
			],
		},
	],
};

export default sidebars;
