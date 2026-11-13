import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";

import {
	check,
	findings,
	parseBaseline,
	violations,
	type BaselineEntry,
} from "./check-no-legacy.ts";

const server = "server/application/src/main/java/example/Settings.java";
const route = "webapp/src/routes/previous.tsx";
const entry: BaselineEntry = {
	rule: "route-alias",
	file: route,
	text: 'throw redirect({ to: "/current" });',
	count: 1,
	digest: "a".repeat(64),
	owner: "LEGACY #4179",
};
const actual = [{ ...entry, line: 4 }];
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
await describe("the baseline", async () => {
	await it("admits only the recorded number of existing occurrences", () => {
		assert.deepEqual(violations(actual, [entry], [entry], "1.0.0-rc.6"), []);
		assert.ok(
			violations([...actual, { ...entry, line: 5 }], [entry], [entry], "1.0.0-rc.6").length > 0,
		);
	});
	await it("refuses a new baseline entry or a higher occurrence count", () => {
		assert.ok(violations(actual, [entry], [], "1.0.0-rc.6").some((e) => e.includes("cannot grow")));
		assert.ok(
			violations(actual, [{ ...entry, count: 2 }], [entry], "1.0.0-rc.6").some((e) =>
				e.includes("cannot grow"),
			),
		);
	});
	await it("requires removal of stale entries", () =>
		assert.ok(violations([], [entry], [entry], "1.0.0-rc.6").some((e) => e.includes("stale"))));
	await it("requires an empty baseline for stable 1.0 and later versions", () => {
		for (const version of ["1.0.0", "1.1.0", "2.0.0"]) {
			assert.ok(violations(actual, [entry], [entry], version).some((e) => e.includes("empty")));
		}
		assert.deepEqual(violations([], [], [entry], "1.0.0"), []);
	});
	await it("validates the parsed trust boundary", () => {
		for (const invalid of [
			"{}",
			"[{}]",
			JSON.stringify([{ ...entry, count: 0 }]),
			JSON.stringify([{ ...entry, owner: "" }]),
			JSON.stringify([entry, entry]),
		]) {
			assert.throws(() => parseBaseline(invalid));
		}
		assert.deepEqual(parseBaseline(JSON.stringify([entry])), [entry]);
	});
});

await it("scans untracked source and refuses a baseline added beside the violation", () => {
	const root = mkdtempSync(path.join(tmpdir(), "no-legacy-"));
	try {
		const git = (...args: string[]) =>
			execFileSync("git", args, { cwd: root, env: environmentForGitFixture(), stdio: "pipe" });
		mkdirSync(path.join(root, "scripts"));
		writeFileSync(path.join(root, "package.json"), JSON.stringify({ version: "1.0.0-rc.6" }));
		writeFileSync(path.join(root, "scripts/no-legacy-baseline.json"), "[]\n");
		git("init", "--quiet");
		git("add", ".");
		git(
			"-c",
			"commit.gpgsign=false",
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.test",
			"commit",
			"--quiet",
			"-m",
			"base",
		);
		git("update-ref", "refs/remotes/origin/main", "HEAD");
		assert.deepEqual(check(root, "origin/main"), []);
		const source = "/** @deprecated */\nexport const previous = true;";
		writeFileSync(path.join(root, "scripts/previous.ts"), source);
		assert.ok(check(root, "origin/main").some((error) => error.includes("source-deprecation")));
		const current = findings("scripts/previous.ts", source);
		writeFileSync(
			path.join(root, "scripts/no-legacy-baseline.json"),
			JSON.stringify(current.map((finding) => ({ ...finding, count: 1, owner: "new exemption" }))),
		);
		assert.ok(check(root, "origin/main").some((error) => error.includes("cannot grow")));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

await it("does not mistake a path default for an alias", () => {
	const source = `cache="\${TRIVY_CACHE_DIR:-$HOME/.cache/trivy}"`;
	assert.deepEqual(findings(".github/actions/download/action.yml", source), []);
});
await it("rejects changed redirect lists inside a baseline file", () => {
	const file = "docs/docusaurus.config.ts";
	const source = 'const config = { redirects: [{from: "/a", to: "/b"}] };';
	const original = findings(file, source);
	// The plugin marker is present in real docs configuration. Use the JSON form here.
	const json = '{"redirects":[{"from":"/a","to":"/b"}]}';
	const before = findings(file, json).map((finding) => ({ ...finding, count: 1, owner: "LEGACY" }));
	const after = findings(file, json.replace("/b", "/b2"));
	assert.ok(violations(after, before, before, "1.0.0-rc.6").length > 0);
	assert.ok(original.some((finding) => finding.rule === "docs-redirect"));
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
await it("scans an untracked HTML redirect stub through the real git boundary", () => {
	const root = mkdtempSync(path.join(tmpdir(), "no-legacy-html-"));
	try {
		const git = (...args: string[]) =>
			execFileSync("git", args, { cwd: root, env: environmentForGitFixture(), stdio: "pipe" });
		mkdirSync(path.join(root, "scripts"));
		writeFileSync(path.join(root, "package.json"), '{"version":"1.0.0-rc.6"}');
		writeFileSync(path.join(root, "scripts/no-legacy-baseline.json"), "[]\n");
		git("init", "--quiet");
		git("add", ".");
		git(
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.test",
			"commit",
			"--quiet",
			"-m",
			"base",
		);
		git("update-ref", "refs/remotes/origin/main", "HEAD");
		mkdirSync(path.join(root, "docs/static/previous"), { recursive: true });
		writeFileSync(
			path.join(root, "docs/static/previous/index.html"),
			'<meta http-equiv="refresh" content="0; url=/current">',
		);
		assert.ok(check(root, "origin/main").some((error) => error.includes("docs-redirect")));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

await it("the first baseline admits only unchanged debt from the base commit", () => {
	const root = mkdtempSync(path.join(tmpdir(), "no-legacy-bootstrap-"));
	try {
		const git = (...args: string[]) =>
			execFileSync("git", args, { cwd: root, env: environmentForGitFixture(), stdio: "pipe" });
		mkdirSync(path.join(root, "scripts"));
		mkdirSync(path.join(root, "docs"));
		writeFileSync(path.join(root, "package.json"), '{"version":"1.0.0-rc.6"}');
		const file = "docs/docusaurus.config.ts";
		const source = '{\n"redirects":[\n{"from":"/old","to":"/current"}\n]\n}';
		writeFileSync(path.join(root, file), source);
		git("init", "--quiet");
		git("add", ".");
		git(
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.test",
			"commit",
			"--quiet",
			"-m",
			"base",
		);
		git("update-ref", "refs/remotes/origin/main", "HEAD");
		const baseline = findings(file, source).map((finding) => ({
			...finding,
			count: 1,
			owner: "LEGACY",
		}));
		const baselinePath = path.join(root, "scripts/no-legacy-baseline.json");
		writeFileSync(baselinePath, JSON.stringify(baseline));
		assert.deepEqual(check(root, "origin/main"), []);
		const changed = source.replace('"/current"', '"/current"},{"from":"/another","to":"/current"');
		writeFileSync(path.join(root, file), changed);
		assert.ok(check(root, "origin/main").some((error) => error.includes("source of a baseline")));
		writeFileSync(
			baselinePath,
			JSON.stringify(
				findings(file, changed).map((finding) => ({ ...finding, count: 1, owner: "LEGACY" })),
			),
		);
		assert.ok(check(root, "origin/main").some((error) => error.includes("cannot grow")));
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

await it("source fingerprints do not depend on checkout line endings", () => {
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
		assert.ok(violations(found, [], [], "1.0.0-rc.6").length > 0, source);
	}
});
