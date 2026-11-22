import assert from "node:assert/strict";

/**
 * The names a tool command may see: what the tool passes, written out here so that a wider allow-list fails the test,
 * and what bash sets for itself.
 */
const VISIBLE = new Set([
	"PATH",
	"HOME",
	"LANG",
	"TMPDIR",
	"GIT_CONFIG_COUNT",
	"GIT_TERMINAL_PROMPT",
	"GIT_ATTR_NOSYSTEM",
	"GIT_PAGER",
	"GIT_OPTIONAL_LOCKS",
	"PWD",
	"OLDPWD",
	"SHLVL",
	"_",
]);

/** The names and values of an `env` listing. */
function listed(text: string): Map<string, string> {
	return new Map(
		text
			.split("\n")
			.filter((line) => line.includes("="))
			.map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]),
	);
}

/** Fails unless a tool command saw only the tool environment and none of the given secrets. */
export function assertToolEnvironment(text: string, secrets: readonly string[]): void {
	const variables = listed(text);
	assert.ok(variables.has("PATH"), "the command still finds its programs");
	for (const name of variables.keys()) {
		assert.ok(
			VISIBLE.has(name) || /^GIT_CONFIG_(?:KEY|VALUE)_\d+$/u.test(name),
			`a tool command sees ${name}`,
		);
	}
	for (const secret of secrets) {
		assert.ok(!text.includes(secret), "a tool command sees a credential");
	}
}
