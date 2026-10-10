import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { parseDocument } from "yaml";
import { asString, parseJson } from "./lib/json.ts";

const read = (file: string) => readFileSync(new URL(`../${file}`, import.meta.url), "utf8");
const edge = read("docker/traefik/dynamic.yml");
const nginx = read("webapp/docker/workspace-subdomains.conf.template");

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
	const redirectLabels = /\^\/w\/\((?<labels>[^)]+)\)/u.exec(nginx)?.groups?.labels;
	for (const actual of [edgeLabels, redirectLabels]) {
		assert.ok(actual !== undefined);
		assert.deepEqual(new Set(actual.split("|")), expected);
	}
});

await test("only the webapp gets public runtime settings and only the proxy gets DNS credentials", () => {
	const proxy = read("docker/compose.proxy.yaml");
	assert.match(proxy, /path: \$\{TRAEFIK_DNS_ENV_FILE:/u);
	for (const file of [
		"docker/compose.app.yaml",
		"docker/compose.core.yaml",
		"docker/preview/compose.app.yaml",
	]) {
		assert.doesNotMatch(read(file), /CF_DNS_API_TOKEN|TRAEFIK_DNS_ENV_FILE/u);
	}
	assert.match(read("webapp/docker/security-headers.conf"), /connect-src 'self' https:/u);
	assert.match(
		read("webapp/Dockerfile"),
		/NGINX_ENVSUBST_FILTER="\^HEPHAESTUS_WORKSPACE_SUBDOMAINS_\(ENABLED\|BASE_DOMAIN\)\$"/u,
	);
});

await test("the proxy and nginx use the server's DNS base-domain constraints", () => {
	const server = read(
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/WorkspaceSubdomainProperties.java",
	);
	const encoded = /Pattern.compile\(\s*(?<pattern>"[^"]*")/u.exec(server)?.groups?.pattern;
	assert.ok(encoded !== undefined);
	const pattern = `^${asString(parseJson(encoded), "domain pattern").replaceAll("(?:", "(")}$`;
	const shell = /readonly WORKSPACE_BASE_DOMAIN_PATTERN='(?<pattern>[^']*)'/u.exec(
		read("webapp/docker/entrypoint.sh"),
	)?.groups?.pattern;
	assert.equal(shell, pattern);
	assert.ok(edge.includes(pattern.replaceAll(String.raw`\.`, "[.]")));
});

await test("nginx startup rejects untrusted template values before writing configuration", () => {
	for (const domain of [
		"",
		"https://example.com",
		"Example.com",
		`${"a".repeat(64)}.com`,
		'example.com"; #',
		"example.com\n}",
		"example.123",
	]) {
		const result = spawnSync(
			"bash",
			[fileURLToPath(new URL("../webapp/docker/entrypoint.sh", import.meta.url))],
			{
				encoding: "utf8",
				env: {
					...process.env,
					HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED: "true",
					HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN: domain,
					APPLICATION_CLIENT_URL: `https://${domain}`,
					APPLICATION_SERVER_URL: `https://${domain}/api`,
				},
			},
		);
		assert.equal(result.status, 1, domain);
		assert.match(result.stderr, /Subdomains require a DNS base domain/u);
	}
});
