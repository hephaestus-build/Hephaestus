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
export const serverStaticGates = ["gate:java-nullness"];
export const changesetGates = ["gate:changesets", "gate:docs-lint", "gate:prose"];

export const pushStaticTasks = {
	agents: agentStaticGates,
	changesets: changesetGates,
	docs: [...docsStaticGates, "gate:prose"],
	extension: extensionGates,
	// One Gradle invocation: formatting, PMD, test compilation and the architecture tier.
	server: [...serverStaticGates, "check:push:server"],
	// The build enforces the bundle budget; it takes seconds, and the browser suites build the same bundle.
	webapp: [...webappGates, "verification:webapp-build"],
};
