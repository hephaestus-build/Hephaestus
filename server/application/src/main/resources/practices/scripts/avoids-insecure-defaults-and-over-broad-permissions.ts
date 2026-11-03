import { definePracticeScript } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

export const LABELS = {
	"credential-named-value-in-url-path-query":
		"A selected line that puts a credential-named value into a URL path or query.",
	"keychain-write":
		"A selected keychain write, whose accessibility class comes from kSecAttrAccessible.",
	"logs-credential-named-value-response-body":
		"A selected log or print call whose arguments name a token, a password, a credential or a response body.",
	"insecure-transport-tls-off":
		"A selected line that allows plain HTTP or turns off the verification of TLS certificates.",
	"over-broad-permission-wildcard":
		"A selected line that grants world-writable file modes, permits all requests, or allows every origin.",
	"credential-named-literal":
		"A selected line that assigns a literal value to a credential-named field.",
};
type InsecureKind = keyof typeof LABELS;

const PATTERNS: [InsecureKind, RegExp][] = [
	[
		"credential-named-value-in-url-path-query",
		/["'`](?:https?:\/\/|\/)[^"'`\n]*(?:\\\(|[$#]\{)[^"'`\n)}]*\b(?:token|secret|password|api[_-]?key|auth|session|refresh)\w*/iu,
	],
	// kSecAttrAccessible defaults to WhenUnlocked; the review must judge whether that class fits the secret.
	["keychain-write", /\bSecItemAdd\b|kSecValueData\b/u],
	[
		"logs-credential-named-value-response-body",
		/\b(?:print|NSLog|console\.(?:log|debug|info)|System\.out\.print\w*|log\.(?:debug|info|warn))\s*\([^)]*\b(?:token|password|secret|credential|response|body|account)\b/iu,
	],
	[
		"insecure-transport-tls-off",
		/NSAllowsArbitraryLoads|allowsArbitraryLoads|rejectUnauthorized\s*:\s*false|InsecureSkipVerify\s*:\s*true|verify\s*=\s*False|\.insecure\(\)|http:\/\/(?!localhost|127\.0\.0\.1|0\.0\.0\.0)/u,
	],
	[
		"over-broad-permission-wildcard",
		/chmod\s+0?777|\b0o?777\b|permitAll\(\)|\.anyRequest\(\)\.permitAll|Access-Control-Allow-Origin["'\s:]*\*|allowedOrigins?["'\s:]*\*/u,
	],
	[
		"credential-named-literal",
		/\b(?:password|secret|api[_-]?key|token)\s*[:=]\s*["'`][A-Za-z0-9_\-/+]{8,}["'`]/iu,
	],
];

function isComment(t: string): boolean {
	return t.startsWith("//") || t.startsWith("#") || t.startsWith("*") || t.startsWith("/*");
}

function countFact(kind: InsecureKind): string {
	return kind.replaceAll(/-(?<letter>[a-z])/gu, (_, letter: string) => letter.toUpperCase());
}

const RULES = PATTERNS.map(([kind, test]) => ({ kind, label: LABELS[kind], test }));

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request", "scm.file");
		const found = await work.sites(RULES, { tests: true, comments: true });
		const sites = found.sites.filter((site) => !isComment(site.text));
		const leads = sites.map((site) => site.lead);
		const byKind: Record<string, number> = {};
		if (found.filesScanned > 0) {
			for (const [kind] of PATTERNS) {
				byKind[countFact(kind)] = sites.filter((site) => site.kind === kind).length;
			}
		}
		const directions =
			leads.length > 0
				? [
						`Found ${leads.length} insecure-default or secret-exposure candidate(s) on selected lines. Investigate each on the reachable auth and transport surface.`,
						"For credential-named URL, literal and logging leads, establish the value’s role under class 5 of the standard before deciding whether it exposes credential material.",
						"A keychain write takes its accessibility from kSecAttrAccessible, WhenUnlocked when omitted, which is not a defect by itself.",
						"Disabled TLS, wildcard CORS and world-writable permissions are over-broad. Confirm whether each is real and exploitable before deciding.",
					]
				: [];
		return ctx.review({
			leads: leads.slice(0, 40),
			facts: { insecureDefaultCandidates: leads.length, ...byKind },
			directions,
		});
	},
});
