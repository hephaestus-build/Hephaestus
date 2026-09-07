import { readFileSync } from "node:fs";
import { posix } from "node:path";

import { parse } from "jsonc-parser";
import type { OxfmtConfig } from "oxfmt";
import { defineConfig } from "vite-plus";

const asStringArray = (value: unknown): string[] | undefined =>
	Array.isArray(value) && value.every((entry) => typeof entry === "string") ? value : undefined;

// oxlint-disable-next-line typescript/no-unsafe-type-assertion
const formatConfig = parse(
	readFileSync(new URL(".oxfmtrc.json", import.meta.url), "utf8"),
) as OxfmtConfig;
const fmt = {
	...formatConfig,
	ignorePatterns: [...(formatConfig.ignorePatterns ?? []), "**/*.md", "**/*.html"],
};

// The one home of every repository command; `package.json` keeps only `prepare`, which pnpm runs
// itself. A cached task owns its command and its verdict is a pure function of tracked files;
// anything that spawns outside the tree, reads the environment, runs tests or has side effects is
// uncached. `scripts/check-runner-contract.ts` proves the runner facts this file relies on.
const run = (
	command: string | string[],
	{ cwd, dependsOn = [] }: { cwd?: string; dependsOn?: string[] } = {},
) => ({ command, cwd, dependsOn, cache: false as const });
type CacheableCommand<Command extends string> =
	Command extends `${string}vp ${"run" | "exec"}${string}` ? never : Command;
// pnpm rewrites node_modules/.modules.yaml on every install; no verdict reads it.
const cached = <Command extends string>(command: CacheableCommand<Command>) => ({
	command,
	input: [{ auto: true }, "!node_modules/.modules.yaml"],
});
// For a verdict the tracker cannot see through a subprocess: the inputs are named. A cached task
// runs with a filtered environment, so anything the command reads from it is named in `env` and
// becomes part of the fingerprint.
const cachedOn = <Command extends string>(
	command: CacheableCommand<Command>,
	input: string[],
	{ env = [], dependsOn = [] }: { env?: string[]; dependsOn?: string[] } = {},
) => ({ command, input, output: [], env, dependsOn });
// A rejection nothing exercises stops rejecting: these two calls must not compile.
// @ts-expect-error `vp run <task>` resolves to another node, which the runner never caches
void cached("vp run check");
// @ts-expect-error `vp exec` runs a bundled binary the runner does not fingerprint, wherever it sits
void cachedOn("node scripts/x.ts && vp exec oxlint .", ["package.json"]);
// No command of its own: runs its dependencies in parallel and fails when any of them fails.
const group = (dependsOn: readonly string[]) => ({
	command: [],
	dependsOn: [...dependsOn],
	cache: false as const,
});

const webappSources = "'webapp/**/*.{js,jsx,ts,tsx,json,jsonc,css}'";
const agentSources =
	"'server/application/src/{main,test}/resources/agent/**/*.ts' 'server/application/src/main/resources/practices/precompute/**/*.ts' 'docker/agents/precompute/**/*.ts' 'scripts/**/*.ts'";
const loadSources = "'load-tests/**/*.js'";
const docsSources = "'docs/**/*.{js,jsx,ts,tsx,json,jsonc,css}'";
// Two passes: a negation applies to the whole invocation, so `!*/**` would also drop the nested set.
const rootConfigSources = "'*.{json,ts,code-workspace}' '!*/**'";
const nestedConfigSources = "'{.changeset,.vscode,scripts}/*.{cjs,json}'";
// `as const` keeps the command a literal type, so `cached` can still read what it runs.
const configFormatCommand = (mode: "--check" | "--write") =>
	`vp fmt ${mode} ${rootConfigSources} && vp fmt ${mode} ${nestedConfigSources}` as const;
const oxlintTargets = "server docker scripts .changeset .github commitlint.config.ts";
// Decided when the config loads; a command never uses shell expansion.
const oxlintFormat = process.env.GITHUB_ACTIONS === "true" ? "-f github " : "";
const repoRoot = import.meta.dirname;

// What Maven reads for a format or PMD verdict. `server/.env` is per developer, so it is not an input.
const mavenInputs = [
	"server/**",
	"!server/**/target/**",
	"!server/postgres-data/**",
	"!server/.env",
	"scripts/run-mvnw.ts",
	".java-version",
];
// `server/mvnw` picks the JDK from JAVA_HOME and takes extra goals and JVM flags from these, so all
// three decide the verdict.
const mavenEnv = ["JAVA_HOME", "MAVEN_ARGS", "MAVEN_OPTS"];
// The docs lint's file set, plus the trees markdownlint reaches outside `docs/`, read from its own
// config so the fingerprint cannot miss a scope change.
const markdownScope = (
	asStringArray(
		parse(readFileSync(new URL("docs/.markdownlint-cli2.jsonc", import.meta.url), "utf8")).globs,
	) ?? []
)
	.filter((glob) => glob.startsWith("../"))
	.map((glob) => posix.normalize(`docs/${glob}`));
const docsLintInputs = [
	"docs/**",
	"!docs/build/**",
	"!docs/.docusaurus/**",
	"!docs/node_modules/**",
	...markdownScope,
	"webapp/tools/oxlint/**",
	".oxlintrc.json",
	"tsconfig.json",
	"pnpm-lock.yaml",
];

const mvnw = "node scripts/run-mvnw.ts";
const integrationTests = process.env.HEPHAESTUS_INTEGRATION_TESTS ?? "";
const integrationShard = integrationTests
	? ` -Dtest=${integrationTests} -Dsurefire.failIfNoSpecifiedTests=false`
	: "";

// The gates, by the tree they judge. `quality` runs all of them; each CI job runs one tree's set.
const policyGates = [
	"gate:toolchain",
	"gate:runner-contract",
	"gate:lint-contract",
	"gate:agent-runtime-pins",
	"gate:changesets",
	"gate:instructions",
	"gate:contracts",
	"gate:preview-stack",
	"gate:env",
];
const serverGates = ["gate:java-nullness", "gate:server"];
const webappGates = [
	"gate:webapp",
	"gate:webapp-format",
	"gate:components",
	"gate:stories",
	"gate:story-sort",
];
const agentGates = ["gate:agents", "gate:agent-tests"];
const docsGates = ["gate:docs", "gate:diagrams", "gate:docs-tokens"];
const loadGates = ["gate:load-format"];
const checkTasks = [
	...policyGates,
	...serverGates,
	...webappGates,
	...agentGates,
	...docsGates,
	...loadGates,
];
// What the Windows runner cannot run: Maven against a JDK it does not provision, Docker, and the
// agent specs, which run the Linux sandbox runtime. Everything else is expected to pass there; a
// gate that fails on Windows is a portability defect, not a reason to add it here.
const linuxOnly = ["gate:server", "gate:agent-tests", "gate:preview-stack"];

export default defineConfig({
	fmt,
	defaultPackage: "./webapp",
	run: {
		tasks: {
			// `check` and `verify` are the names people type; `quality` and `verification` are the
			// graphs they run.
			check: group(["quality"]),
			verify: group(["verification"]),
			quality: group(checkTasks),
			// Each leg saturates the machine on its own, so they run one after another.
			verification: run([
				"vp run quality",
				"vp run verification:webapp-tests",
				"vp run verification:storybook-tests",
				"vp run verification:server-tests",
				"vp run verification:webapp-build",
				"vp run verification:storybook-build",
				"vp run verification:docs-build",
			]),
			"check:affected": run("node scripts/check-affected.ts"),

			// Formatting
			format: group([
				"format:java",
				"format:webapp",
				"format:agents",
				"format:load",
				"format:docs",
				"format:config",
			]),
			"format:check": group([
				"gate:server-format",
				"gate:webapp-format",
				"gate:agents-format",
				"gate:load-format",
				"gate:docs-format",
				"gate:config-format",
			]),
			"format:java": run(`${mvnw} -pl application spotless:apply -q`),
			"format:java:check": group(["gate:server-format"]),
			"format:webapp": run(`vp fmt --write ${webappSources}`),
			"format:webapp:check": group(["gate:webapp-format"]),
			"format:agents": run(`vp fmt --write ${agentSources}`),
			"format:agents:check": group(["gate:agents-format"]),
			"format:load": run(`vp fmt --write ${loadSources}`),
			"format:load:check": group(["gate:load-format"]),
			"format:docs": run(`vp fmt --write ${docsSources}`),
			"format:docs:check": group(["gate:docs-format"]),
			"format:config": run(configFormatCommand("--write")),
			"format:config:check": group(["gate:config-format"]),
			"format:achievements": run("node scripts/format-achievements.ts"),

			// Lint and typecheck
			lint: group(["gate:server-lint", "lint:webapp", "gate:agents-lint", "gate:docs-lint"]),
			typecheck: group(["typecheck:webapp", "gate:scripts-typecheck", "gate:agents-typecheck"]),
			"lint:java": group(["gate:server-lint"]),
			"lint:java:report": run(
				`${mvnw} -f application/pom.xml compile pmd:pmd && echo 'Report: server/application/target/site/pmd.html'`,
				{ dependsOn: ["prepare:server:generated"] },
			),
			"lint:webapp": run("vp -C webapp lint ."),
			"lint:webapp:fix": run("vp -C webapp lint --fix ."),
			"lint:agents": group(["gate:agents-lint"]),
			"lint:agents:fix": run(`vp exec oxlint --fix ${oxlintTargets}`),
			"typecheck:webapp": run("vp -C webapp lint --type-aware --type-check ."),
			"typecheck:scripts": group(["gate:scripts-typecheck"]),
			"typecheck:agents": group(["gate:agents-typecheck"]),
			"check:webapp": run("vp -C webapp check"),
			"fix:webapp": run("vp -C webapp check --fix"),
			"check:agents": group(["gate:agents"]),
			"fix:agents": run(["vp run format:agents", "vp run format:config", "vp run lint:agents:fix"]),

			// Repository policy
			"gate:toolchain": run("node scripts/check-toolchain.ts"),
			"sync:toolchain-pins": run("node scripts/sync-toolchain-pins.ts"),
			"gate:runner-contract": run("node --test scripts/check-runner-contract.ts"),
			"gate:lint-contract": run("node --test scripts/check-lint-contract.ts"),
			"gate:agent-runtime-pins": cached("node scripts/check-agent-runtime-pins.ts"),
			"gate:preview-stack": run(
				"node scripts/check-preview-stack.ts && node --test scripts/check-preview-stack.test.ts",
			),
			"gate:env": run(
				"node scripts/check-env-defaults.ts && node scripts/check-env-roles.ts && node --test scripts/check-env-roles.test.ts scripts/self-host-setup.test.ts",
			),
			"gate:contracts": run(
				"node scripts/validate-artifact-source-contracts.ts && node scripts/check-artifact-source-contract-immutability.ts && node --test scripts/check-artifact-source-contract-immutability.test.ts",
			),
			"gate:instructions": run(
				"node scripts/check-agent-instructions.ts && node --test scripts/check-agent-instructions.test.ts",
			),
			"gate:changesets": run(
				"node --test scripts/verify-changesets.test.ts scripts/sync-release-version.test.ts",
			),

			// Application server. One Maven process per checkout: the lint waits for the format check.
			"gate:java-nullness": run(
				"node scripts/check-java-nullness.ts && node --test scripts/check-java-nullness.test.ts",
			),
			"gate:server-format": cachedOn(`${mvnw} -pl application spotless:check -q`, mavenInputs, {
				env: mavenEnv,
			}),
			// PMD reads the generated clients, which the install puts in the local repository. That
			// install is a side effect no cache replays, so it is its own uncached dependency.
			"gate:server-lint": cachedOn(
				`${mvnw} -f application/pom.xml compile pmd:check -q`,
				mavenInputs,
				{ env: mavenEnv, dependsOn: ["prepare:server:generated"] },
			),
			"gate:server": run(["vp run gate:server-format", "vp run gate:server-lint"]),
			"gate:pmd-canary": run("node scripts/check-pmd-canary.ts"),
			"prepare:server:generated": run(
				`${mvnw} -pl generated-clients -am install -DskipTests --batch-mode`,
			),
			"test:server:unit": run(
				`${mvnw} -pl application -am package -Dspring-boot.repackage.skip=true -Dsurefire.includedGroups=unit -DskipCoverage=false --batch-mode`,
			),
			"test:server:architecture": run(
				`${mvnw} -pl application -am package -Dspring-boot.repackage.skip=true -Parchitecture-tests --batch-mode`,
			),
			"test:server:verification": run(
				`${mvnw} -pl application -am package -Dspring-boot.repackage.skip=true -Parchitecture-tests -Dsurefire.includedGroups=unit,architecture -DskipCoverage=false --batch-mode`,
			),
			// CI runs the tier as shards. The selector is decided here, at config load, so the workflow
			// passes a matrix value through the environment rather than into a command; locally the
			// whole tier runs. failIfNoSpecifiedTests=false is for the generated-clients module, which
			// has no tests for any selector.
			"test:server:integration": run(
				`${mvnw} -pl application -am package -Dspring-boot.repackage.skip=true -Dsurefire.includedGroups=integration${integrationShard} -Dparallel=none --batch-mode`,
			),
			"test:server:mutation": run("node scripts/run-security-mutations.ts"),
			"test:postgres-restore": run("node scripts/postgres-backup-restore-test.ts"),

			// Webapp
			// `vp check` is format plus lint; the format half is `gate:webapp-format`, so one failure
			// stays one verdict.
			"gate:webapp": cached("vp -C webapp check --no-fmt"),
			"gate:webapp-format": cached(`vp fmt --check ${webappSources}`),
			"gate:components": cached("node scripts/check-presentational-components.ts"),
			"gate:stories": cached("node scripts/check-story-prose.ts"),
			"gate:story-sort": cached("node scripts/check-story-sort.ts"),
			"gate:docs-tokens": cached(
				"node scripts/check-docs-tokens.ts && node --test scripts/check-docs-tokens.test.ts",
			),
			"verification:webapp-tests": group(["test:webapp"]),
			"test:webapp": run("vp run --filter webapp test"),
			"test:webapp:stories": run("vp run --filter webapp test:storybook"),
			"build:webapp": run("vp -C webapp build"),
			"dev:webapp": run("vp -C webapp dev"),

			// Agent runtime, precompute, repository scripts and tooling config
			"gate:agents-format": cached(`vp fmt --check ${agentSources}`),
			"gate:config-format": cached(configFormatCommand("--check")),
			"gate:agents-lint": run(`vp exec oxlint ${oxlintFormat}${oxlintTargets}`),
			"gate:agents-typecheck": cached("tsc -p tsconfig.agents.json --noEmit"),
			"gate:scripts-typecheck": cached("tsc -p scripts/tsconfig.json --noEmit"),
			"gate:release-image-inventory": cached("node scripts/check-release-image-inventory.ts"),
			"gate:tooling-tests": group(["test:tooling"]),
			"gate:agents": group([
				"gate:agents-format",
				"gate:config-format",
				"gate:agents-lint",
				"gate:agents-typecheck",
				"gate:scripts-typecheck",
				"gate:release-image-inventory",
				"gate:tooling-tests",
			]),
			"gate:agent-tests": group(["test:agents"]),
			"test:agents": run(
				"node --test server/application/src/test/resources/agent/*.spec.ts docker/agents/precompute/*.test.ts docker/agents/precompute/lib/*.test.ts",
			),
			"test:tooling": run("node --test scripts/*.test.ts"),

			// Load tests
			"gate:load-format": cached(`vp fmt --check ${loadSources}`),
			"gate:load-syntax": group(["test:load:syntax"]),
			"test:load:webhook-burst": run("node scripts/load-test.ts webhook-burst"),
			"test:load:detection-mentor": run("node scripts/load-test.ts detection-mentor"),
			"report:load:baseline": run("node scripts/load-test.ts report"),
			"test:load:syntax": run("node scripts/load-test.ts syntax"),

			// Docs
			"gate:docs-format": cached(`vp fmt --check ${docsSources}`),
			"gate:docs-lint": cachedOn(
				"vp -C docs lint --type-aware --type-check . && vp -C docs exec markdownlint-cli2",
				docsLintInputs,
			),
			"gate:diagrams": cached("node scripts/check-mermaid-diagrams.ts"),
			"gate:docs": group(["gate:docs-format", "gate:docs-lint"]),
			"docs:lint": group(["gate:docs-lint"]),
			"docs:dev": run("vp run --filter docs start"),
			"docs:build": run("vp run --filter docs build"),
			"docs:serve": run("vp run --filter docs serve"),

			// What each CI job runs.
			"ci:server": group(serverGates.concat("gate:contracts", "gate:env")),
			"ci:tooling": group([
				// Excluded here because another job or workflow runs them; the Windows leg re-runs what it can.
				...policyGates.filter(
					(gate) =>
						!["gate:contracts", "gate:env", "gate:changesets", "gate:preview-stack"].includes(gate),
				),
				...agentGates,
				...docsGates,
				...loadGates,
				"gate:load-syntax",
			]),
			"ci:webapp:static": group([...webappGates, "gate:docs-tokens", "verification:webapp-tests"]),
			// The build regenerates the route tree, so it never runs beside a gate that reads it. A
			// second name after `vp run` is an argument, not a second task, so the two are separate.
			"ci:webapp": run(["vp run ci:webapp:static", "vp run verification:webapp-build"]),
			"ci:windows": group(checkTasks.filter((gate) => !linuxOnly.includes(gate))),

			// Scoped selections for check:affected
			"affected:agents": group(agentGates),
			"affected:docs": group([...docsGates, "gate:instructions"]),
			"affected:server": group(serverGates),
			"affected:webapp": group(webappGates),

			// The credential-free builds and suites that verification adds to quality
			"verification:storybook-tests": group(["test:webapp:stories"]),
			"verification:webapp-build": run("node scripts/verify-webapp-build.ts"),
			"verification:storybook-build": run("vp run --filter webapp build-storybook"),
			"verification:docs-build": group(["docs:build"]),
			"verification:server-tests": group(["test:server:verification"]),

			// Generated artefacts, schema and integration schemas
			"generate:api": run(["vp run generate:api:specs", "vp run generate:api:client"]),
			"generate:api:specs": run("node scripts/generate-openapi-spec.ts"),
			"generate:api:client": run(
				"node scripts/rm.ts webapp/src/api && vp run --filter webapp generate:api",
			),
			"db:draft-changelog": run("node scripts/db-utils.ts draft-changelog"),
			"db:check-drift": run("node scripts/db-utils.ts check-drift"),
			"db:generate-erd-docs": run("node scripts/db-utils.ts generate-erd"),
			"schema:github": run("node scripts/update-github-schema.ts"),
			"schema:gitlab": run("node scripts/update-gitlab-schema.ts"),
			"schema:outline": run("node scripts/update-outline-spec.ts"),
			"schema:nats": run("node scripts/nats-extract-examples.ts"),
			"report:test-results": run("node scripts/summarize-test-results.ts"),
			"release:version": run("changeset version && node scripts/sync-release-version.ts"),

			// Local development
			dev: run("mprocs"),
			"dev:compose": run("docker compose up -d --wait", { cwd: "server" }),
			"dev:compose:e2e": run("docker compose up -d --wait postgres nats", { cwd: "server" }),
			"dev:compose:down": run("docker compose down", { cwd: "server" }),
			"dev:reset": run(
				"docker compose down -v && node ../scripts/rm.ts postgres-data && docker compose up -d --wait",
				{ cwd: "server" },
			),
			"dev:server": run(`${mvnw} -f application/pom.xml spring-boot:run`, {
				dependsOn: ["dev:compose", "prepare:server:generated"],
			}),
			"dev:server:e2e": run(`${mvnw} -f application/pom.xml spring-boot:run -Dapp.profiles=e2e`, {
				dependsOn: ["dev:compose:e2e", "prepare:server:generated"],
			}),
			"check:ports": run("node scripts/check-ports.ts"),
			"dev:e2e:setup": run("node scripts/e2e-setup.ts"),
			"dev:public-test": run("node scripts/jean-public-test.ts"),
		},
	},
});
