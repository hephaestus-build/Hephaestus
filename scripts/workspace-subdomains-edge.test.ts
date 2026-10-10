import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { parseDocument } from "yaml";
import { asString, parseJson } from "./lib/json.ts";

const read = (file: string) => readFileSync(new URL(`../${file}`, import.meta.url), "utf8");
const compose = parseDocument(read("docker/compose.proxy.yaml"));
const edge = asString(compose.get("x-traefik-dynamic"), "edge template").replaceAll("$$", "$");
const start = asString(compose.get("x-traefik-start"), "proxy validation").replaceAll("$$", "$");

await test("the file-provider TLS default requires TLS 1.2", () => {
	const configuration = parseDocument(edge.slice(0, edge.indexOf("{{")));
	assert.equal(configuration.getIn(["tls", "options", "default", "minVersion"]), "VersionTLS12");
});

await test("tenant host and redirect policies reserve every server label", () => {
	const server = read(
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/validation/WorkspaceSlugValidator.java",
	);
	const labels = [
		...server
			.slice(
				server.indexOf("private static final Set<String> RESERVED"),
				server.indexOf("static Set<String> reservedLabels"),
			)
			.matchAll(/"(?<label>[a-z0-9-]+)"/gu),
	].map((match) => match.groups?.label);
	assert.ok(labels.length > 0);
	const expected = new Set([...labels, "pr[0-9]+"]);
	const edgeLabels = /\$reserved := "(?<labels>[^"]+)"/u.exec(edge)?.groups?.labels;
	assert.ok(edgeLabels !== undefined);
	assert.deepEqual(new Set(edgeLabels.split("|")), expected);
});

await test("DNS credential values stay outside Compose and only the proxy mounts them", () => {
	const proxy = read("docker/compose.proxy.yaml");
	assert.doesNotMatch(proxy, /env_file:/u);
	assert.match(proxy, /:\/run\/secrets\/dns:ro/u);
	assert.match(start, /export "\$variable=\/run\/secrets\/dns\/token"/u);
	for (const file of [
		"docker/compose.app.yaml",
		"docker/compose.core.yaml",
		"docker/preview/compose.app.yaml",
	]) {
		assert.doesNotMatch(read(file), /CF_DNS_API_TOKEN|TRAEFIK_DNS_CREDENTIAL/u);
	}
	assert.match(read("webapp/docker/security-headers.conf"), /connect-src 'self' https:/u);
});

await test("proxy startup and routing use the server's DNS base-domain constraints", () => {
	const server = read(
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/core/WorkspaceSubdomainProperties.java",
	);
	const encoded = /Pattern.compile\(\s*(?<pattern>"[^"]*")/u.exec(server)?.groups?.pattern;
	assert.ok(encoded !== undefined);
	const pattern = `^${asString(parseJson(encoded), "domain pattern").replaceAll("(?:", "(")}$`;
	assert.ok(start.includes(pattern.replaceAll(String.raw`\.`, "[.]")));
});

await test("tenant hosts and apex redirects use the server's slug constraints", () => {
	const server = read(
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/validation/WorkspaceSlugValidator.java",
	);
	const pattern = /LABEL_PATTERN = "(?<pattern>[^"]+)"/u.exec(server)?.groups?.pattern;
	assert.ok(pattern !== undefined);
	assert.equal(pattern, "^(?!.*--)[a-z0-9][a-z0-9-]{1,49}[a-z0-9]$");
	assert.match(server, /MIN_LENGTH = 3;/u);
	assert.match(server, /MAX_LENGTH = 51;/u);
	assert.ok(edge.includes("HostRegexp(`^[a-z0-9][a-z0-9-]{1,49}[a-z0-9][.]"));
	assert.ok(edge.includes("!HostRegexp(`^.*--.*$`)"));
	assert.ok(edge.includes("PathRegexp(`^/w/[a-z0-9][a-z0-9-]{1,49}[a-z0-9](/|$)`)"));
	assert.ok(edge.includes("!PathRegexp(`^/w/[a-z0-9-]*--`)"));
	assert.ok(edge.includes("!PathRegexp(`^/w/({{ $reserved }})(/|$)`"));
});
