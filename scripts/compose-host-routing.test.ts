import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { isSet } from "./lib/env.ts";

const STACKS = ["app", "core", "proxy"] as const;

/** Every `traefik.http.routers.<name>.<key>=` value in a stack's Compose file. */
function labels(stack: (typeof STACKS)[number], key: string): { router: string; value: string }[] {
	const file = readFileSync(new URL(`../docker/compose.${stack}.yaml`, import.meta.url), "utf8");
	const pattern = new RegExp(`traefik\\.http\\.routers\\.([a-z0-9-]+)\\.${key}=(.*?)"?$`, "gmu");
	return [...file.matchAll(pattern)].flatMap(([, router, value]) =>
		router !== undefined && isSet(value) ? [{ router: `${stack}/${router}`, value }] : [],
	);
}

const rules = STACKS.flatMap((stack) => labels(stack, "rule"));
const priorities = new Map(
	STACKS.flatMap((stack) => labels(stack, "priority")).map(({ router, value }) => [
		router,
		Number(value),
	]),
);

/** The index of the parenthesis that closes the one at index 0, or -1 if the rule opens with none. */
function endOfLeadingGroup(rule: string): number {
	if (!rule.startsWith("(")) {
		return -1;
	}
	let depth = 0;
	for (let i = 0; i < rule.length; i += 1) {
		if (rule[i] === "(") {
			depth += 1;
		} else if (rule[i] === ")") {
			depth -= 1;
			if (depth === 0) {
				return i;
			}
		}
	}
	return -1;
}

await test("a router that routes on the served host set groups its matcher", () => {
	// Traefik binds && tighter than ||, so `Host(a) || Host(b) && PathPrefix(/webhooks)` matches
	// host a on *every* path. On the webhook router, which outranks the others, that hands the
	// whole site to the webhook service. Grouping the matcher is what prevents it, and it is
	// asserted for every router that names the matcher rather than only the ones combining today:
	// an ungrouped matcher reads as correct right up until someone appends a condition to it.
	// The closing parenthesis is found by counting depth, not by looking at the last character —
	// `(A) || (B) && PathPrefix(/)` also starts with `(` and ends with `)` and has the bug.
	const matcherRules = rules.filter(({ value }) => value.includes("APP_HOST_MATCH"));
	assert.notEqual(
		matcherRules.length,
		0,
		"no router routes on APP_HOST_MATCH; the pattern is stale",
	);

	for (const { router, value } of matcherRules) {
		const close = endOfLeadingGroup(value);
		assert.notEqual(close, -1, `${router} does not group its host matcher: ${value}`);
		const rest = value.slice(close + 1).trim();
		assert.ok(
			rest === "" || rest.startsWith("&&"),
			`${router} continues its host matcher outside the group: ${value}`,
		);
	}
});

await test("the canonical redirect takes the browser and leaves the API and the webhooks alone", () => {
	// An open tab and a provider still posting to an older name keep working through a domain move
	// only while /api and /webhooks outrank the redirect, and the redirect only reaches a browser
	// while it outranks the SPA router. Traefik falls back to rule length when priorities tie, which
	// nobody edits on purpose, so the promise install.mdx makes rests on these four numbers.
	const canonical = priorities.get("app/https-canonical");
	assert.notEqual(canonical, undefined, "the canonical-redirect router is gone");

	assert.ok(
		Number(canonical) > Number(priorities.get("app/https-webapp")),
		"the canonical redirect must outrank the SPA router or it never fires",
	);
	for (const router of ["app/https-application-server", "core/https-webhook-server"]) {
		assert.ok(
			Number(canonical) < Number(priorities.get(router)),
			`${router} must outrank the canonical redirect or it stops answering on the other names`,
		);
	}
});

await test("what an operator is told to copy is one host per Host()", () => {
	// Traefik v3 takes one host per Host(): Host(`a`,`b`) is rejected and the router never loads, so
	// an example in that shape would take an instance down. The docs carry exactly that as a
	// counter-example on purpose, so every occurrence is extracted and then split by what it is for:
	// anything assigned to APP_HOST_MATCH is configuration an operator pastes, the rest is prose.
	// The extraction is asserted non-empty per file — a pattern that quietly matches nothing is how
	// the counter-example stayed invisible to this gate.
	for (const path of [
		"../docker/.env.example",
		"../docker/self-host/.env.example",
		"../docs/admin/install.mdx",
	]) {
		const text = readFileSync(new URL(path, import.meta.url), "utf8");
		const occurrences = text
			.split("\n")
			.flatMap((line) => [...line.matchAll(/Host\([^()]*\)/gu)].map(([host]) => ({ line, host })));

		assert.notEqual(
			occurrences.length,
			0,
			`${path} documents no Host() at all; the pattern is stale`,
		);
		for (const { line, host } of occurrences) {
			if (!line.includes("APP_HOST_MATCH=")) {
				continue;
			}
			assert.doesNotMatch(
				host,
				/,/u,
				`${path} tells an operator to write a multi-host Host(): ${host}`,
			);
		}
	}
});

await test("every https router sets HSTS itself, not through the edge", () => {
	// The proxy stack puts security-headers on its https entrypoint, which covers a deployment whose
	// edge is that stack. Staging and every preview run behind someone else's proxy, where no
	// entrypoint middleware of ours exists — and the omission is invisible, because the webapp's
	// nginx sets the other security headers on its own responses. HSTS is the one no response can
	// set for itself, so each router that terminates a public request carries it.
	for (const stack of ["app", "core"] as const) {
		const file = readFileSync(new URL(`../docker/compose.${stack}.yaml`, import.meta.url), "utf8");
		const routers = [
			...file.matchAll(/traefik\.http\.routers\.(?<name>https-[a-z-]+)\.rule=/gu),
		].map((match) => match.groups?.name);
		assert.ok(routers.length > 0, `${stack} declares no https router`);
		for (const router of routers) {
			const attached = new RegExp(`routers\\.${router}\\.middlewares=([^"\n]*)`, "u").exec(
				file,
			)?.[1];
			assert.ok(
				attached?.includes("hsts") === true,
				`${stack}/${router} would serve without HSTS behind a proxy that is not ours`,
			);
		}
	}
});

await test("capability-link pages suppress referrers before scripts or assets load in every deployment", () => {
	const html = readFileSync(new URL("../webapp/index.html", import.meta.url), "utf8");
	const policy = html.indexOf('<meta name="referrer" content="no-referrer" />');
	assert.ok(
		policy !== -1 && policy < html.search(/<(?:script|link)\b/u),
		"static referrer policy must precede assets, not wait for the SPA",
	);
	const nginx = readFileSync(
		new URL("../webapp/docker/security-headers.conf", import.meta.url),
		"utf8",
	);
	assert.match(nginx, /add_header Referrer-Policy "no-referrer" always;/u);
	for (const file of [
		"../docker/compose.proxy.yaml",
		"../docker/self-host/compose.single-host.yaml",
	]) {
		assert.match(
			readFileSync(new URL(file, import.meta.url), "utf8"),
			/traefik\.http\.middlewares\.security-headers\.headers\.referrerPolicy=no-referrer/u,
		);
	}
});
