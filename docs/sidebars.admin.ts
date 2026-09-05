import type { SidebarsConfig } from "@docusaurus/plugin-content-docs";

const sidebars: SidebarsConfig = {
	adminSidebar: [
		{ type: "doc", id: "overview", label: "Overview" },
		{ type: "doc", id: "install", label: "Install (Self-Hosted)" },
		{ type: "doc", id: "github-integration", label: "GitHub Integration" },
		{ type: "doc", id: "ai-providers", label: "Connect an AI Provider" },
		{
			type: "category",
			label: "Practices",
			collapsed: false,
			items: [
				{ type: "doc", id: "practice-catalog", label: "Practice Catalog" },
				{ type: "doc", id: "practice-review", label: "Practice Review" },
				{ type: "doc", id: "practice-review-operations", label: "Practice Review Operations" },
			],
		},
		{ type: "doc", id: "webhook-ingestion-operations", label: "Webhook Ingestion Operations" },
		{ type: "doc", id: "observability", label: "Observability" },
		{ type: "doc", id: "backup-restore", label: "Backup & Restore" },
		{ type: "doc", id: "credential-key-rotation", label: "Credential Key Rotation" },
		{ type: "doc", id: "production-setup", label: "Integrations & Reference Deployment" },
		{ type: "doc", id: "compatibility-policy", label: "Compatibility Policy" },
		{ type: "doc", id: "runtime-roles", label: "Runtime Roles" },
		{ type: "doc", id: "configuration-readiness", label: "Configuration Readiness" },
		{ type: "doc", id: "threat-model", label: "Threat Model" },
		{ type: "doc", id: "release-image-lock", label: "Release image lock" },
		{ type: "doc", id: "pull-based-deployment", label: "Pull-Based Deployment" },
		{ type: "doc", id: "buildpacks-cds-decision", label: "Server image build (Buildpacks + CDS)" },
		{ type: "doc", id: "legal-pages", label: "Legal Pages" },
		{
			type: "category",
			label: "Data-Protection Documentation",
			link: { type: "doc", id: "dsms/dsms" },
			items: [
				{ type: "doc", id: "dsms/record-of-processing", label: "Record of Processing (Art. 30)" },
				{ type: "doc", id: "dsms/dpia-prescreen", label: "DPIA Pre-Screen (Art. 35)" },
				{ type: "doc", id: "dsms/processor-checklist", label: "Processor Checklist (Art. 28)" },
				{ type: "doc", id: "dsms/artifact-source-governance", label: "Artifact-Source Governance" },
				{ type: "doc", id: "dsms/personal-data-map", label: "Personal-Data Map" },
			],
		},
	],
};

export default sidebars;
