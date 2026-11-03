import { definePracticeScript, listWithin, type ReviewLead } from "@hephaestus/practice-scripts";
import { scm } from "@hephaestus/practice-scripts/scm";

const KEY_PREFIXES = [
	["sk-", "OpenAI-style secret key"],
	["ghp_", "GitHub personal access token"],
	["gho_", "GitHub OAuth token"],
	["AKIA", "AWS access key"],
	["xoxb-", "Slack bot token"],
	["xoxp-", "Slack user token"],
	["AIza", "Google API key"],
	["pk_live_", "Stripe live publishable key"],
	["sk_live_", "Stripe live secret key"],
	["pk_test_", "Stripe test key"],
	["sk_test_", "Stripe test key"],
] as const;
const CONFIG_EXTENSIONS = new Set(
	"plist json yml yaml xcconfig env properties toml xml ini conf cfg sh".split(" "),
);
const SECRET_NAME = /(?:apiKey|api_key|token|secret|password|pwd|auth|credential|API_KEY)\s*=\s*"/u;
const PLACEHOLDER = /YOUR_|REPLACE|changeme|xxx|placeholder|example|TODO/iu;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;

const LABELS = {
	knownPrefix: "A changed line with a quoted string that starts like a known key or token.",
	privateKey: "A changed line with a private key header.",
	secretAssignment: "A changed line that assigns a long, varied string to a secret-named variable.",
	connectionString: "A changed line with a database URL that carries a user and password.",
	longString: "A changed line with a long, varied quoted string.",
} as const;
type Cue = keyof typeof LABELS;

/** Shannon entropy per character. */
function entropy(value: string): number {
	const counts = new Map<string, number>();
	for (const char of value) {
		counts.set(char, (counts.get(char) ?? 0) + 1);
	}
	let bits = 0;
	for (const count of counts.values()) {
		const p = count / value.length;
		bits -= p * Math.log2(p);
	}
	return Math.round(bits * 100) / 100;
}

function scannable(path: string, language: string | null): boolean {
	return language !== null || CONFIG_EXTENSIONS.has(path.split(".").pop()?.toLowerCase() ?? "");
}

export default definePracticeScript({
	meta: {},
	async run(ctx) {
		const work = scm(ctx, "scm.pull_request");
		const files = await work.sources((file) => scannable(file.path, file.language));
		const counts = new Map<Cue, number>();
		const keyKinds = new Set<string>();
		const leads: ReviewLead[] = [];
		const cue = (
			key: Cue,
			file: string,
			line: number,
			facts: Record<string, string | number> = {},
		) => {
			counts.set(key, (counts.get(key) ?? 0) + 1);
			if (leads.length < 60) {
				leads.push({ at: { file, line }, label: LABELS[key], facts });
			}
		};
		for (const file of files) {
			for (const [index, line] of file.text.split("\n").entries()) {
				const at = index + 1;
				if (!file.isAdded(at)) {
					continue;
				}
				const comment = line.trimStart().startsWith("//");
				let marked = false;
				for (const [prefix, kind] of KEY_PREFIXES) {
					if (!comment && line.includes(`"${prefix}`)) {
						keyKinds.add(kind);
						cue("knownPrefix", file.path, at, { keyKind: kind });
						marked = true;
					}
				}
				if (/BEGIN.*PRIVATE KEY/u.test(line)) {
					cue("privateKey", file.path, at);
					marked = true;
				}
				const named = /"(?<name>[^"]{8,})"/u.exec(line)?.groups?.name;
				if (
					!comment &&
					SECRET_NAME.test(line) &&
					!/Logger\s*\(|\.bundleIdentifier/u.test(line) &&
					named !== undefined &&
					named.length >= 10 &&
					!PLACEHOLDER.test(named) &&
					entropy(named) >= 3
				) {
					cue("secretAssignment", file.path, at, {
						entropy: entropy(named),
						valueLength: named.length,
					});
					marked = true;
				}
				if (/(?:postgres|mysql|mongodb|redis):\/\/[^"]*:[^"]*@/u.test(line)) {
					cue("connectionString", file.path, at);
					marked = true;
				}
				const long = /"(?<name>[a-zA-Z0-9+/]{20,})"/u.exec(line)?.groups?.name;
				if (
					!marked &&
					!comment &&
					long !== undefined &&
					!/subsystem:|category:|\.bundleIdentifier/u.test(line) &&
					entropy(long) >= 3.5 &&
					!UUID.test(long) &&
					!/\/|http|www/u.test(long) &&
					!/^[a-z]+\.[a-z]+\./iu.test(long)
				) {
					cue("longString", file.path, at, { entropy: entropy(long), valueLength: long.length });
				}
			}
		}
		const directions = [
			"Each lead marks a changed line, not a confirmed credential. Read whether the value is a real secret, a placeholder, a test fixture or a public identifier. Never repeat a suspected value in feedback; name the file and line instead.",
			`Scanned the added lines of ${String(files.length)} changed source or configuration file(s). A secret in a binary, an unknown file type or an unchanged line is not seen here.`,
		];
		if (keyKinds.size > 0) {
			directions.push(
				`Key shapes seen: ${listWithin([...keyKinds], 120)}. A real key in a commit stays in the history after it is removed, so the authored practice expects it to be revoked and replaced, not only deleted.`,
			);
		}
		return ctx.review({
			leads,
			facts: Object.fromEntries([...counts].map(([key, value]) => [key, value])),
			directions,
		});
	},
});
