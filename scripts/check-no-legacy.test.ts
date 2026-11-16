import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";

import { check, findings } from "./check-no-legacy.ts";

const server = "server/application/src/main/java/example/Settings.java";
const route = "webapp/src/routes/previous.tsx";
await describe("owned source and configuration", async () => {
	for (const [file, source] of [
		[server, "@Deprecated\nclass Settings {}"],
		[server, '@java.lang.Deprecated(since = "1.0")\nclass Settings {}'],
		["webapp/src/lib/old.ts", "/** @deprecated Use current. */\nexport const old = 1;"],
		[server, '@DeprecatedConfigurationProperty(replacement = "current")'],
		[server, 'new ConfigurationPropertyNameAliases("current", "old")'],
		[server, '@JsonAlias("old")'],
		["scripts/setting.ts", "const token = process.env.CURRENT ?? process.env.OLD;"],
		[server, 'environment.getProperty("current", environment.getProperty("old"))'],
		["server/application/src/main/resources/application.yml", `key: \${CURRENT:\${OLD:}}`],
		[
			"deploy/chart/hephaestus/templates/server.yaml",
			"{{ .Values.current | default .Values.old }}",
		],
		["deploy/chart/hephaestus/templates/server.yaml", "{{ coalesce .Values.current .Values.old }}"],
	] satisfies [string, string][]) {
		await it(`rejects ${source}`, () => assert.ok(findings(file, source).length > 0));
	}
	for (const [file, source] of [
		[
			"server/generated-clients/src/main/resources/graphql/github/schema.github.graphql",
			'field: String @deprecated(reason: "upstream")',
		],
		["server/application/src/test/java/example/Test.java", "@Deprecated class Fixture {}"],
		["webapp/src/api/types.gen.ts", "/** @deprecated */ type Generated = string;"],
		[
			"server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/github/jackson/GitHubIssueMixin.java",
			'@JsonAlias("issueState")',
		],
		[
			"server/application/src/main/resources/application.yml",
			`issuer: \${ISSUER:http://localhost:\${PORT:8080}}`,
		],
	] satisfies [string, string][]) {
		await it(`permits the current external contract or ordinary default in ${file}`, () =>
			assert.deepEqual(findings(file, source), []));
	}
});
await describe("route, server and docs redirects", async () => {
	for (const [file, source] of [
		[
			route,
			'createFileRoute("/previous")({ beforeLoad: () => { throw redirect({ to: "/current" }); } });',
		],
		[server, "response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());"],
		[server, 'return "forward:/current";'],
		["webapp/docker/nginx.conf", "return 308 /current;"],
		["docs/previous.mdx", "---\nredirect_to: /current\n---"],
		["docs/previous.mdx", '<meta http-equiv="refresh" content="0; url=/current" />'],
		["docs/src/pages/previous.tsx", '<Redirect to="/current" />'],
		["docs/docusaurus.config.ts", '"@docusaurus/plugin-client-redirects", { redirects: [] }'],
	] satisfies [string, string][]) {
		await it(`rejects ${source}`, () => assert.ok(findings(file, source).length > 0));
	}
	await it("permits authentication and current landing routes", () => {
		assert.deepEqual(
			findings(
				route,
				'beforeLoad: () => { if (!authenticated) throw redirect({ to: "/login" }); }, component: Page',
			),
			[],
		);
		assert.deepEqual(
			findings(
				"webapp/src/routes/index.tsx",
				'beforeLoad: () => { throw redirect({ to: "/home" }); }',
				["webapp/src/routes/index.tsx", "webapp/src/routes/home.tsx"],
			),
			[],
		);
	});
});
await it("scans tracked and untracked source through the real git boundary", () => {
	const root = mkdtempSync(path.join(tmpdir(), "no-legacy-"));
	try {
		execFileSync("git", ["init", "--quiet"], {
			cwd: root,
			env: environmentForGitFixture(),
			stdio: "pipe",
		});
		mkdirSync(path.join(root, "scripts"));
		writeFileSync(path.join(root, "scripts/current.ts"), "export const current = true;\n");
		assert.deepEqual(check(root), []);
		writeFileSync(
			path.join(root, "scripts/previous.ts"),
			"/** @deprecated */\nexport const previous = true;",
		);
		assert.ok(check(root).some((error) => error.includes("source-deprecation")));
		mkdirSync(path.join(root, "docs/static/previous"), { recursive: true });
		writeFileSync(
			path.join(root, "docs/static/previous/index.html"),
			'<meta http-equiv="refresh" content="0; url=/current">',
		);
		assert.ok(check(root).some((error) => error.includes("docs-redirect")));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

await it("does not mistake a path default for an alias", () => {
	const source = `cache="\${TRIVY_CACHE_DIR:-$HOME/.cache/trivy}"`;
	assert.deepEqual(findings(".github/actions/download/action.yml", source), []);
});
await it("rejects the common permanent redirect forms and component disguises", () => {
	for (const [file, source] of [
		[server, "response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());"],
		[server, 'response.sendRedirect("/current");'],
		["webapp/docker/nginx.conf", "return 301 https://example.test/current;"],
		["webapp/docker/nginx.conf", "return 308 $old_destination;"],
		["webapp/docker/nginx.conf", "rewrite ^/previous$ /current permanent;"],
		[route, 'beforeLoad: () => { return redirect({to: "/current"}); }, component: () => null'],
		[
			"webapp/src/routes/previous/index.tsx",
			'beforeLoad: () => { throw redirect({to: "/current"}); }',
		],
	] satisfies [string, string][]) {
		assert.ok(findings(file, source).length > 0, source);
	}
	const current = 'if ($workspace_redirect != "") { return 308 $workspace_redirect; }';
	assert.ok(
		findings("deploy/chart/hephaestus/templates/workspace-routing.yaml", current).some(
			(finding) => finding.rule === "path-alias",
		),
	);
});

await it("findings do not depend on checkout line endings", () => {
	const text = "/** @deprecated */\nexport const previous = true;\n";
	assert.deepEqual(
		findings("scripts/previous.ts", text),
		findings("scripts/previous.ts", text.replaceAll("\n", "\r\n")),
	);
});
await it("a route redirect cannot hide behind an import name or expression body", () => {
	const source =
		'import { redirect as move } from "@tanstack/react-router";\nconst Route = {beforeLoad: () => move({to: "/current"})};';
	assert.ok(findings(route, source).some((finding) => finding.rule === "route-alias"));
});

await it("an extra workspace redirect map entry cannot use the canonical return exemption", () => {
	const file = "deploy/chart/hephaestus/templates/workspace-routing.yaml";
	const original = readFileSync(path.join(import.meta.dirname, "../", file), "utf8");
	assert.deepEqual(findings(file, original), []);
	const changed = original.replace(
		'default "";',
		'default "";\n      "~^old-host:/retired" "https://current.example/";',
	);
	assert.ok(findings(file, changed).some((finding) => finding.rule === "path-alias"));
});
await it("numeric Java redirects and absolute redirect views are refused", () => {
	for (const source of [
		"response.setStatus(308);",
		"ResponseEntity.status(301);",
		"HttpStatus.valueOf(308);",
		'return "redirect:https://example.test/current";',
	]) {
		assert.ok(findings(server, source).some((finding) => finding.rule === "path-alias"));
	}
});

await it("parentheses cannot hide route redirect expression bodies", () => {
	for (const expression of [
		'redirect({to: "/current"})',
		'(redirect({to: "/current"}))',
		'( ( redirect({to: "/current"}) ) )',
	]) {
		assert.ok(
			findings(route, `const Route = {beforeLoad: () => ${expression}};`).some(
				(finding) => finding.rule === "route-alias",
			),
		);
	}
});

await it("a changed canonical workspace source or destination is refused", () => {
	const file = "deploy/chart/hephaestus/templates/workspace-routing.yaml";
	const original = readFileSync(path.join(import.meta.dirname, "../", file), "utf8");
	for (const changed of [
		original.replace("(?:GET|HEAD):/w/", "(?:GET|HEAD):/retired/"),
		original.replace("https://$workspace_slug.", "https://old.$workspace_slug."),
	]) {
		assert.ok(findings(file, changed).some((finding) => finding.rule === "path-alias"));
	}
});

await it("refuses a re-added redirect plugin or config in each supported docs form", () => {
	for (const [file, source] of [
		["docs/package.json", '{"dependencies":{"@docusaurus/plugin-client-redirects":"3.10.2"}}'],
		["docs/docusaurus.config.ts", 'plugins: ["@docusaurus/plugin-client-redirects"]'],
		["docs/docusaurus.config.ts", 'const config = {redirects: [{from: "/old", to: "/current"}]};'],
		["docs/docusaurus.config.ts", 'const config = {createRedirects(path) {return ["/old"];}};'],
		["docs/docusaurus.config.ts", 'const config = {createRedirects: (path) => ["/old"]};'],
		["docs/redirects.json", '{"redirects":[{"from":"/old","to":"/current"}]}'],
	] satisfies [string, string][]) {
		const found = findings(file, source);
		assert.ok(
			found.some((finding) => finding.rule === "docs-redirect"),
			source,
		);
	}
});

await it("keeps released docs addresses only through the one redirect map", () => {
	const map = 'export const RELEASED_REDIRECTS = {"/admin/old": "/admin/current"};';
	assert.deepEqual(findings("docs/src/lib/released-redirects.ts", map), []);
	assert.deepEqual(findings("docs/astro.config.ts", "redirects: RELEASED_REDIRECTS,"), []);
	assert.deepEqual(
		findings(
			"docs/astro.config.ts",
			"redirects: Object.fromEntries(\n\t\tObject.entries(RELEASED_REDIRECTS).map(([from, to]) => [from, to]),\n\t),",
		),
		[],
	);
	assert.ok(
		findings(
			"docs/astro.config.ts",
			'redirects: RELEASED_REDIRECTS,\nconst extra = {redirects: {"/old": "/current"}};',
		).some((finding) => finding.rule === "docs-redirect"),
	);
	assert.ok(
		findings(
			"docs/src/lib/other-redirects.ts",
			'const config = {redirects: {"/old": "/current"}};',
		).some((finding) => finding.rule === "docs-redirect"),
	);
});

await it("only the renamed-slug redirect of the workspace filter is a current flow", () => {
	const file =
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/context/WorkspaceContextFilter.java";
	const original = readFileSync(path.join(import.meta.dirname, "../", file), "utf8");
	const redirect = "response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());";
	assert.deepEqual(findings(file, original), []);
	assert.ok(
		findings(file, original.replace(redirect, `${redirect}\n        ${redirect}`)).some(
			(finding) => finding.rule === "path-alias",
		),
	);
	assert.ok(
		findings(
			file,
			original.replaceAll(
				"workspaceSlugHistoryRepository.findFirstByOldSlugOrderByChangedAtDesc(",
				"lookup(",
			),
		).some((finding) => finding.rule === "path-alias"),
	);
	assert.ok(findings(server, redirect).some((finding) => finding.rule === "path-alias"));
});
