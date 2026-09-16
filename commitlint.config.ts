// Keep this module dependency-free: PR validation imports it without a workspace install.

// Allowed commit types. Types categorize history for readers only — versioning
// and changelog entries come from changesets (.changeset/*.md), not commit types.
const TYPES = [
	"feat",
	"fix",
	"docs",
	"style",
	"refactor",
	"perf",
	"test",
	"build",
	"ci",
	"chore",
	"revert",
];

const SCOPES = [
	"webapp",
	"server",
	"docs",

	"deps",
	"security",
	"db",
	"docker",

	"ci",
	// NOT for: application.yml (use 'server'), Dockerfiles (use service scope)
	"config",
	"deps-dev",
	"scripts",
	"release",

	"auth",
	"integration",
	"scm",
	"leaderboard",
	"mentor",
	"notifications",
	"profile",
	"teams",
	"workspace",
];

// A breaking change is carried by a changeset, which is what sets the next version; a marker in the
// title only says so where nothing reads it.
const BREAKING_MARKER = /^[^:]*!:/u;
const SUBJECT_SHAPE = /^(?!.*\.$).*\S$/u;
const HELP_URL = "https://github.com/hephaestus-build/Hephaestus/blob/main/CONTRIBUTING.md";

interface ParsedCommit {
	readonly type?: string | null;
	readonly scope?: string | null;
	readonly subject?: string | null;
	readonly header?: string | null;
}

type RuleOutcome = [valid: boolean, message?: string];

const helpfulErrorsPlugin = {
	rules: {
		"type-enum-helpful": (parsed: ParsedCommit): RuleOutcome => {
			const { type } = parsed;
			if (type == null || type === "") {
				return [true];
			}
			const valid = TYPES.includes(type);
			return [
				valid,
				valid
					? ""
					: `type "${type}" is not allowed.\n\n` +
						`Allowed types:\n` +
						`  ${TYPES.join(", ")}\n\n` +
						`Format: <type>(<scope>): <description>\n` +
						`Example: feat(webapp): add user profile page`,
			];
		},
		"scope-enum-helpful": (parsed: ParsedCommit): RuleOutcome => {
			const { scope } = parsed;
			if (scope == null || scope === "") {
				return [true];
			}
			const valid = SCOPES.includes(scope);
			return [
				valid,
				valid
					? ""
					: `scope "${scope}" is not allowed.\n\n` +
						`Allowed scopes:\n` +
						`  ${SCOPES.join(", ")}\n\n` +
						`⚠️  'config' is for developer tooling\n` +
						`    For runtime config use 'server', for Dockerfiles use service scope\n\n` +
						`Format: <type>(<scope>): <description>\n` +
						`Example: fix(server): reject invalid requests`,
			];
		},
		"subject-shape": (parsed: ParsedCommit): RuleOutcome => [
			SUBJECT_SHAPE.test(parsed.subject ?? ""),
			"description must not be empty or end with a period",
		],
		"breaking-marker-absent": (parsed: ParsedCommit): RuleOutcome => [
			!BREAKING_MARKER.test(parsed.header ?? ""),
			"drop the ! marker and describe the breaking change in a changeset",
		],
	},
};

// The one home for what CI enforces on a pull request title: it reads this object from the default
// branch without a workspace install, and the rules below hold a commit message to the same shapes.
export const pullRequestTitlePolicy = {
	types: TYPES.join("\n"),
	scopes: SCOPES.join("\n"),
	headerMaxLength: 100,
	headerPattern: String.raw`^(\w+)(?:\(([\w-]+)\))?: (.+)$`,
	subjectPattern: SUBJECT_SHAPE.source,
	breakingMarkerPattern: BREAKING_MARKER.source,
	helpUrl: HELP_URL,
};

const configuration = {
	extends: ["@commitlint/config-conventional"],
	plugins: [helpfulErrorsPlugin],
	helpUrl: HELP_URL,
	rules: {
		"type-enum-helpful": [2, "always"],
		"scope-enum-helpful": [2, "always"],
		"subject-shape": [2, "always"],
		"breaking-marker-absent": [2, "always"],
		"type-enum": [0],
		"scope-enum": [0],
		"scope-empty": [0],
		// Preserve technical names such as GraphQL and OAuth.
		"subject-case": [0],
		"subject-full-stop": [0],
		"subject-empty": [0],
		"type-empty": [2, "never"],
		"type-case": [2, "always", "lower-case"],
		"header-max-length": [2, "always", pullRequestTitlePolicy.headerMaxLength],
	},
};

export default configuration;
