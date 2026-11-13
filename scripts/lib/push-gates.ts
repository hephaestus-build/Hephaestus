export const PUSH_CHECK_ENV = "HEPHAESTUS_PUSH_CHECK";

export const agentStaticGates = [
	"gate:agents-format",
	"gate:config-format",
	"gate:agents-lint",
	"gate:agents-typecheck",
	"gate:scripts-typecheck",
];
export const docsStaticGates = ["gate:docs-format", "gate:docs-lint"];
export const webappGates = ["gate:webapp", "gate:webapp-format", "gate:components", "gate:stories"];
export const extensionGates = [
	"gate:extension",
	"gate:extension-format",
	"gate:components",
	"gate:stories",
];
export const serverStaticGates = ["gate:java-nullness", "gate:openapi-lint"];
export const changesetGates = ["gate:changesets", "gate:docs-lint", "gate:prose"];

// Each scope runs what `check:affected` runs for it, except the exclusions that
// `scripts/check-push.test.ts` names with their reasons. Each added gate takes seconds.
export const pushStaticTasks = {
	agents: [
		...agentStaticGates,
		"gate:release-image-inventory",
		"gate:adr-confirmation",
		"gate:no-legacy",
	],
	changesets: changesetGates,
	docs: [
		...docsStaticGates,
		"gate:prose",
		"gate:diagrams",
		"gate:system-diagrams",
		"gate:docs-pages",
		"gate:adr-confirmation",
		"gate:instructions",
		"gate:no-legacy",
	],
	extension: [...extensionGates, "gate:adr-confirmation", "gate:no-legacy"],
	// One Gradle invocation: formatting, PMD, test compilation and the architecture tier.
	server: [
		...serverStaticGates,
		"check:push:server",
		"gate:adr-confirmation",
		"gate:api-pagination",
		"gate:no-legacy",
	],
	// The build enforces the bundle budget; it takes seconds, and the browser suites build the same bundle.
	webapp: [
		...webappGates,
		"gate:prose",
		"gate:adr-confirmation",
		"gate:no-legacy",
		"verification:webapp-build",
	],
};
