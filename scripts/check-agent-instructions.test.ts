import assert from "node:assert/strict";
import { resolve } from "node:path";
import { test } from "node:test";

import {
	analyse,
	CODEX_SKILLS,
	codeSpans,
	parse,
	references,
	type Snapshot,
	scan,
	type TrackedFile,
	withoutCode,
} from "./check-agent-instructions.ts";

const REPO_ROOT = resolve(import.meta.dirname, "..");

/**
 * The one failure a case produced. Most cases break exactly one thing, so a second failure is a
 * check firing where it should not — asserted here rather than left for a positional index to hide.
 */
function only(failures: readonly string[]): string {
	assert.equal(failures.length, 1, `expected one failure, got:\n${failures.join("\n")}`);
	return failures[0] ?? "";
}

/** A symlink to `target`, as an override value. */
const symlinkTo = (target: string): { readonly target: string } => ({ target });
const markdownSpans = (markdown: string): readonly string[] => codeSpans(markdown, "markdown");

type Override = string | { readonly target: string } | { readonly kind: "opaque" } | null;

/** A checkout with the wiring intact. `null` removes a file; every other value sets its kind. */
function snapshot(overrides: Record<string, Override> = {}): Snapshot {
	const base: Record<string, string> = {
		"opencode.json": JSON.stringify({ instructions: ["AGENTS.md", "*/AGENTS.md"] }),
		"AGENTS.md": "# Hephaestus\n",
		"CLAUDE.md": "@AGENTS.md\n",
		"webapp/AGENTS.md": "# Webapp\n",
		"webapp/CLAUDE.md": "@AGENTS.md\n",
		".claude/skills/land-pr/SKILL.md":
			"---\nname: land-pr\ndisable-model-invocation: true\n---\n\n# Land PR\n",
		".opencode/commands/land-pr.md":
			"---\ndescription: Land a PR\n---\n\n@.claude/skills/land-pr/SKILL.md\n",
	};
	const files: TrackedFile[] = [];
	for (const [path, content] of Object.entries({ ...base, ...overrides })) {
		if (content === null) continue;
		if (typeof content === "string") files.push({ path, kind: "text", content });
		else if ("target" in content) files.push({ path, kind: "symlink", target: content.target });
		else files.push({ path, kind: "opaque" });
	}
	return { files };
}

await test("the wired checkout passes", () => {
	assert.deepEqual(analyse(snapshot()), []);
});

await test("the repository this gate ships in passes it", async () => {
	assert.deepEqual(analyse(await scan(REPO_ROOT), CODEX_SKILLS), []);
});

await test("a nested AGENTS.md with no CLAUDE.md beside it is unreachable in Claude Code", () => {
	const failure = only(analyse(snapshot({ "webapp/CLAUDE.md": null })));
	assert.match(failure, /webapp\/AGENTS\.md is loaded by every agent except Claude Code/);
	assert.match(failure, /Add webapp\/CLAUDE\.md/);
});

await test("a CLAUDE.md that imports something else does not count as importing its sibling", () => {
	assert.match(
		only(analyse(snapshot({ "webapp/CLAUDE.md": "@../AGENTS.md\n" }))),
		/does not import webapp\/AGENTS\.md/,
	);
});

await test("an AGENTS.md under an agent root is a skill's payload, not a tree that needs a guide", () => {
	for (const path of [
		".claude/skills/react-best-practices/AGENTS.md",
		".opencode/skill/x/AGENTS.md",
	]) {
		assert.deepEqual(analyse(snapshot({ [path]: "# Vendored\n" })), [], path);
	}
	// Under `.agents/skills/` it is also a Codex mirror, so it needs its counterpart to be legal.
	assert.deepEqual(
		analyse(
			snapshot({
				".agents/skills/x/AGENTS.md": "# Vendored\n",
				".claude/skills/x/AGENTS.md": "# Vendored\n",
			}),
		),
		[],
	);
});

await test("a symlinked CLAUDE.md is reported as a symlink and not also as a missing import", () => {
	const failure = only(analyse(snapshot({ "webapp/CLAUDE.md": symlinkTo("AGENTS.md") })));
	assert.match(failure, /webapp\/CLAUDE\.md is a committed symlink/);
	assert.match(failure, /loads the target path as the entire instruction set/);
});

await test("a symlink's remedy is decided by what it points at, not by where it sits", () => {
	// "Delete the link" is right only when the target is already in the checkout. Told to a guide or
	// a skill that links out of the tree it would destroy the only copy.
	assert.match(
		only(
			analyse(snapshot({ ".opencode/skill/land-pr": symlinkTo("../../.claude/skills/land-pr") })),
		),
		/It duplicates \.claude\/skills\/land-pr, which both agents already read\. Delete the link\./,
	);
	for (const path of ["webapp/AGENTS.md", ".claude/skills/land-pr/SKILL.md"]) {
		const failure = analyse(snapshot({ [path]: symlinkTo("../outside-the-checkout.md") }))[0] ?? "";
		assert.match(failure, /is a committed symlink/, path);
		assert.match(failure, /Replace it with a regular file/, path);
	}
});

await test("a symlinked command is one defect, not also a second copy of itself", () => {
	// Its unread body must not also read as a body that fails to reference the skill.
	assert.match(
		only(
			analyse(
				snapshot({
					".opencode/commands/land-pr.md": symlinkTo("../../.claude/skills/land-pr/SKILL.md"),
				}),
			),
		),
		/is a committed symlink/,
	);
});

await test("an agent file this gate did not read is reported, never counted as checked", () => {
	// `opaque` absorbed into "empty" would pass every check that queries the file, silently.
	for (const path of ["webapp/CLAUDE.md", "webapp/AGENTS.md", ".claude/skills/land-pr/SKILL.md"]) {
		assert.match(
			only(analyse(snapshot({ [path]: { kind: "opaque" } }))),
			/was not read, so every check that queries it saw an empty file/,
			path,
		);
	}
});

await test("an import resolving to nothing is reported against the file holding it", () => {
	const failure = only(analyse(snapshot({ "CLAUDE.md": "@AGENTS.md\n@docs/missing.md\n" })));
	assert.match(failure, /CLAUDE\.md references @docs\/missing\.md/);
});

await test("a broken reference inside an AGENTS.md is reported — both tools load that file whole", () => {
	const failure = only(analyse(snapshot({ "AGENTS.md": "# Hephaestus\n\nSee @docs/gone.mdx.\n" })));
	assert.match(failure, /AGENTS\.md references @docs\/gone\.mdx/);
});

await test("an opencode command reference resolves from the repo root, not from the command file", () => {
	// The wired checkout proves the working direction: `@.claude/…` in a command resolves, which it
	// could not if the base were the command's own directory. This is the other direction.
	const failures = analyse(
		snapshot({
			".claude/skills/land-pr/SKILL.md": "---\nname: land-pr\n---\n\n# Land PR\n",
			".opencode/commands/land-pr.md": "---\n---\n\n@skills/x.md\n",
		}),
	);
	assert.match(only(failures), /resolves to skills\/x\.md/);
});

await test("code is not prose: a reference inside a span, a fence or a comment is not a reference", () => {
	for (const markdown of [
		"Import `@AGENTS.md` to load it.",
		"```sh\n@docs/gone.md\n```\n",
		"~~~sh\n@docs/gone.md\n~~~\n",
		"<!-- @docs/gone.md -->",
		"<!--\n@docs/gone.md\n-->\n",
		"<!--<!-- @docs/gone.md -->",
	]) {
		assert.deepEqual(references(markdown), [], markdown);
	}
	// Closing one comment can bring a `<!` and a `--` together; the text after it is not commented
	// out, so it is still read — but no opening delimiter may survive into the result.
	assert.deepEqual(references("<!<!-- -->-- @AGENTS.md"), ["AGENTS.md"]);
	assert.ok(!withoutCode("<!<!-- -->-- @AGENTS.md").includes("<!--"));
	// …and the fence must close, or everything after it would be swallowed.
	assert.deepEqual(references("```sh\nx\n```\n\n@AGENTS.md\n"), ["AGENTS.md"]);
});

await test("the markdown scanner extracts code spans, not fences or comments", () => {
	assert.deepEqual(markdownSpans("Use `scripts/check.ts` and ``a ` b``."), [
		"scripts/check.ts",
		"a ` b",
	]);
	assert.deepEqual(markdownSpans("A `line\nwrap` is one span."), ["line wrap"]);
	assert.deepEqual(markdownSpans("A `CRLF\r\nwrap` is one span."), ["CRLF wrap"]);
	assert.deepEqual(markdownSpans("A `CR\rwrap` is one span."), ["CR wrap"]);
	assert.deepEqual(markdownSpans("` padded ` and `   `"), ["padded", "   "]);
	assert.deepEqual(markdownSpans("<!-- `hidden.md` -->\n```md\n`fenced.md`\n```\n`shown.md`"), [
		"shown.md",
	]);
	assert.deepEqual(markdownSpans("\\`escaped.md\\`\n\n    `indented.md`"), []);
	assert.deepEqual(markdownSpans("`a <!-- literal --> span`"), ["a <!-- literal --> span"]);
	assert.deepEqual(markdownSpans("> ```md\n> `quoted.md`\n> ```\n`shown.md`"), ["shown.md"]);
	assert.deepEqual(codeSpans("<Tabs>\n<TabItem>\nUse `inside.md`\n</TabItem>\n</Tabs>", "mdx"), [
		"inside.md",
	]);
	assert.deepEqual(markdownSpans("`not closed``"), []);
	assert.deepEqual(
		markdownSpans("```md\n`hidden.md`\n``` not a close\n`still-hidden.md`\n```"),
		[],
	);
	assert.deepEqual(markdownSpans("```md\n<!--\n```\n`shown.md`"), ["shown.md"]);
	assert.deepEqual(markdownSpans("<!--\n```md\n-->\n`shown.md`"), ["shown.md"]);
});

await test("contributor docs reject missing repository paths and npm packages", () => {
	const failures = analyse(
		snapshot({
			"package.json": JSON.stringify({ dependencies: { react: "19.0.0" } }),
			"scripts/existing.ts": { kind: "opaque" },
			"docs/contributor/setup.md":
				"Use `scripts/existing.ts`, `react`, `scripts/missing.ts`, `missing-plugin`, and `@missing/package`.\n",
		}),
	);
	assert.equal(failures.length, 3, failures.join("\n"));
	assert.match(failures[0] ?? "", /scripts\/missing\.ts/);
	assert.match(failures[1] ?? "", /missing-plugin/);
	assert.match(failures[2] ?? "", /@missing\/package/);
});

await test("a renamed settings path is not read as a missing package", () => {
	// A release note names both halves of a rename, and the old half is what a shape test bites on:
	// `hephaestus.mentor.docker-cli` ends the way an npm CLI package does. It blocked a release.
	assert.deepEqual(
		analyse(
			snapshot({
				"package.json": JSON.stringify({ dependencies: {} }),
				"MIGRATION.md":
					"Rename `hephaestus.mentor.docker-cli` to `hephaestus.sandbox.docker.cli`.\n",
			}),
		),
		[],
	);
	// A package name is still a package name: one dot is what npm's own dotted names have.
	assert.match(
		only(
			analyse(
				snapshot({
					"package.json": JSON.stringify({ dependencies: {} }),
					"docs/contributor/setup.md": "Install `lodash.merge-cli`.\n",
				}),
			),
		),
		/lodash\.merge-cli/,
	);
});

await test("an intentional non-checkout path is allowed only in the document that owns it", () => {
	assert.deepEqual(
		analyse(
			snapshot({
				"server/existing.ts": { kind: "opaque" },
				"docs/contributor/local-development.mdx": "Create `server/.env`.\n",
			}),
		),
		[],
	);
	assert.match(
		only(
			analyse(
				snapshot({
					"server/existing.ts": { kind: "opaque" },
					"docs/contributor/setup.md": "Create `server/.env`.\n",
				}),
			),
		),
		/server\/.env/,
	);
});

await test("a unique shorthand directory resolves independently of its descendant count", () => {
	assert.deepEqual(
		analyse(
			snapshot({
				"webapp/src/features/a.ts": { kind: "opaque" },
				"webapp/src/features/b.ts": { kind: "opaque" },
				"docs/contributor/setup.md": "Use `src/features/`.\n",
			}),
		),
		[],
	);
});

await test("an invalid contributor MDX document is reported with its path", () => {
	assert.throws(
		() => analyse(snapshot({ "docs/contributor/broken.mdx": "<Component/ name>" })),
		/docs\/contributor\/broken\.mdx:/,
	);
});

await test("path claims cannot escape through a typo, basename, or unrelated suffix match", () => {
	for (const claim of ["scrips/missing.ts", "missing.md", "./config.md"]) {
		const failure = only(
			analyse(
				snapshot({
					"other/config.md": "# Unrelated\n",
					"docs/contributor/setup.md": `Use \`${claim}\`.\n`,
				}),
			),
		);
		assert.ok(failure.includes(claim), claim);
	}
	assert.deepEqual(
		analyse(
			snapshot({
				"docs/shared.md": { kind: "opaque" },
				"docs/contributor/local.md": { kind: "opaque" },
				"webapp/src/config.ts": { kind: "opaque" },
				"docs/contributor/setup.md": "Use `src/config.ts`, `./local.md`, and `../shared.md`.\n",
			}),
		),
		[],
	);
	assert.match(
		only(
			analyse(
				snapshot({
					"server/src/config.ts": { kind: "opaque" },
					"webapp/src/config.ts": { kind: "opaque" },
					"docs/contributor/setup.md": "Use `src/config.ts`.\n",
				}),
			),
		),
		/src\/config\.ts/,
	);
});

await test("the contributor claim scope is root Markdown and contributor Markdown or MDX only", () => {
	for (const path of ["README.md", "docs/contributor/setup.md", "docs/contributor/setup.mdx"]) {
		assert.match(only(analyse(snapshot({ [path]: "Use `missing.md`.\n" }))), /missing\.md/, path);
	}
	for (const path of ["README.mdx", "docs/reader/setup.md"]) {
		assert.deepEqual(analyse(snapshot({ [path]: "Use `missing.md`.\n" })), [], path);
	}
});

await test("every package.json declaration field satisfies a contributor package claim", () => {
	const manifest = {
		name: "workspace-package",
		dependencies: { dependency: "1" },
		devDependencies: { "dev-package": "1" },
		optionalDependencies: { "optional-package": "1" },
		peerDependencies: { "peer-package": "1" },
	};
	assert.deepEqual(
		analyse(
			snapshot({
				"package.json": JSON.stringify(manifest),
				"docs/contributor/setup.md":
					"`workspace-package` `dependency` `dev-package` `optional-package` `peer-package`\n",
			}),
		),
		[],
	);
	assert.throws(() => analyse(snapshot({ "package.json": "{broken" })), SyntaxError);
});

await test("a code span split by the line wrap does not swallow the import after it", () => {
	assert.deepEqual(
		references("the `<form>` is the level: `flex\nmin-h-0 flex-col`, then @AGENTS.md"),
		["AGENTS.md"],
	);
});

await test("prose that merely contains an @ is not a file reference", () => {
	for (const markdown of [
		"Uses @base-ui/react and @Audited on the mutation.",
		"Pin formatter@0.1.0 exactly.",
		"Upgraded to @v0.74.0 last week.",
		"Ping @felix.dietrich about it.",
		"Types from @/api/types.gen are already checked.",
	]) {
		assert.deepEqual(references(markdown), [], markdown);
	}
});

await test("a real reference survives the markup around it", () => {
	for (const markdown of ["**@AGENTS.md**", "(@AGENTS.md)", "See @AGENTS.md.", "- @AGENTS.md"]) {
		assert.deepEqual(references(markdown), ["AGENTS.md"], markdown);
	}
});

await test("frontmatter is read through a BOM, CRLF and a trailing YAML comment", () => {
	const skill = (fields: string): string => `---\n${fields}\n---\n\n# Land PR\n`;
	for (const [label, content] of [
		["plain", skill("disable-model-invocation: true")],
		["yaml comment", skill("disable-model-invocation: true # typed only")],
		["BOM", `﻿${skill("disable-model-invocation: true")}`],
		["CRLF", skill("disable-model-invocation: true").replaceAll("\n", "\r\n")],
	] as const) {
		const failures = analyse(
			snapshot({
				".claude/skills/land-pr/SKILL.md": content,
				".opencode/commands/land-pr.md": null,
			}),
		);
		assert.match(only(failures), /sets disable-model-invocation/, label);
	}
});

await test("a YAML boolean is read the way YAML spells it, and a non-boolean is reported", () => {
	const skill = (value: string): string =>
		`---\ndisable-model-invocation: ${value}\n---\n\n# Land PR\n`;
	for (const truthy of ["true", "yes", "on", "True", "YES"]) {
		const failures = analyse(
			snapshot({
				".claude/skills/land-pr/SKILL.md": skill(truthy),
				".opencode/commands/land-pr.md": null,
			}),
		);
		assert.match(only(failures), /sets disable-model-invocation/, truthy);
	}
	for (const falsy of ["false", "no", "off"]) {
		assert.deepEqual(
			analyse(
				snapshot({
					".claude/skills/land-pr/SKILL.md": skill(falsy),
					".opencode/commands/land-pr.md": null,
				}),
			),
			[],
			falsy,
		);
	}
	assert.match(
		only(analyse(snapshot({ ".claude/skills/land-pr/SKILL.md": skill("maybe") }))),
		/is not a YAML boolean/,
	);
});

await test("a flag in the body is not frontmatter", () => {
	assert.equal(parse("# Title\n").frontmatter, undefined);
	const split = parse("---\na: 1\n---\n\nbody\n");
	assert.equal(split.frontmatter, "a: 1");
	assert.equal(split.body.trim(), "body");
	const failures = analyse(
		snapshot({
			".claude/skills/land-pr/SKILL.md": "# Land PR\n\ndisable-model-invocation: true\n",
			".opencode/commands/land-pr.md": null,
		}),
	);
	assert.deepEqual(failures, []);
});

await test("an instructions entry that matches no file loads nothing", () => {
	const opencode = JSON.stringify({ instructions: ["AGENTS.md", "*/AGENTS.md", "MISSION.md"] });
	assert.match(
		only(analyse(snapshot({ "opencode.json": opencode }))),
		/lists "MISSION\.md" under instructions/,
	);
});

await test("an AGENTS.md no instructions entry matches is invisible to opencode", () => {
	const opencode = JSON.stringify({ instructions: ["AGENTS.md"] });
	assert.match(
		only(analyse(snapshot({ "opencode.json": opencode }))),
		/webapp\/AGENTS\.md is matched by no opencode\.json instructions entry/,
	);
});

await test("a remote instructions URL is not ours to resolve and is not reported", () => {
	const opencode = JSON.stringify({
		instructions: ["AGENTS.md", "*/AGENTS.md", "https://example.test/rules.md"],
	});
	assert.deepEqual(analyse(snapshot({ "opencode.json": opencode })), []);
});

await test("an opencode.json that cannot be read is a failure, never a stack trace", () => {
	assert.match(only(analyse(snapshot({ "opencode.json": null }))), /opencode\.json is missing/);
	for (const broken of ["{ not json", JSON.stringify({ instructions: "AGENTS.md" })]) {
		const failures = analyse(snapshot({ "opencode.json": broken }));
		assert.ok(
			failures.some((failure) => /could not be read as opencode's config/.test(failure)),
			`${broken} -> ${failures.join("\n")}`,
		);
	}
});

await test("a skill only a typed slash command reaches needs a command in the other tool", () => {
	const failure = only(analyse(snapshot({ ".opencode/commands/land-pr.md": null })));
	assert.match(failure, /sets disable-model-invocation/);
	assert.match(failure, /Add \.opencode\/commands\/land-pr\.md/);
});

await test("a model-invocable skill needs no command", () => {
	const failures = analyse(
		snapshot({
			".claude/skills/land-pr/SKILL.md": "---\nname: land-pr\n---\n\n# Land PR\n",
			".opencode/commands/land-pr.md": null,
		}),
	);
	assert.deepEqual(failures, []);
});

await test("a SKILL.md below a skill root has no command name and is left alone", () => {
	const nested = "---\ndisable-model-invocation: true\n---\n\n# Fragment\n";
	assert.deepEqual(analyse(snapshot({ ".claude/skills/land-pr/rules/SKILL.md": nested })), []);
});

await test("a command that copies the skill body instead of referencing it is two copies", () => {
	const copied = "---\ndescription: Land a PR\n---\n\n# Land PR\n";
	assert.match(
		only(analyse(snapshot({ ".opencode/commands/land-pr.md": copied }))),
		/does not reference @\.claude\/skills\/land-pr\/SKILL\.md/,
	);
});

await test("the same body in two agent directories is caught at the moment it is copied", () => {
	const skill = "---\nname: land-pr\ndisable-model-invocation: true\n---\n\n# Land PR\n";
	assert.match(
		only(analyse(snapshot({ ".opencode/skill/land-pr/SKILL.md": skill }))),
		/\.claude\/skills\/land-pr\/SKILL\.md and \.opencode\/skill\/land-pr\/SKILL\.md hold the same content/,
	);
});

await test("a copy is still a copy when it carries references of its own", () => {
	const body = `---\nname: a\n---\n\n${"See @AGENTS.md for context.\n".repeat(20)}`;
	assert.match(
		only(
			analyse(snapshot({ ".claude/skills/a/SKILL.md": body, ".opencode/skill/a/SKILL.md": body })),
		),
		/hold the same content/,
	);
});

await test("a skill mirrored for Codex is required to be identical, not forbidden", () => {
	// Claude Code reads `.claude/skills/` and Codex reads `.agents/skills/`; neither reads the other,
	// so a skill both must reach exists twice. The pair is allowed; drift in it is not.
	const skill = "---\nname: gh-stack\n---\n\n# gh-stack\n";
	assert.deepEqual(
		analyse(
			snapshot({
				".claude/skills/gh-stack/SKILL.md": skill,
				".agents/skills/gh-stack/SKILL.md": skill,
			}),
		),
		[],
	);
	assert.match(
		only(
			analyse(
				snapshot({
					".claude/skills/gh-stack/SKILL.md": skill,
					".agents/skills/gh-stack/SKILL.md": `${skill}\nCodex-only paragraph.\n`,
				}),
			),
		),
		/has drifted from \.claude\/skills\/gh-stack\/SKILL\.md/,
	);
});

await test("a skill Codex is meant to have cannot quietly become one copy", () => {
	// The regression this list exists for: deleting the Codex half leaves the skill reachable in
	// Claude Code and gone from Codex, with nothing to say so.
	const skill = "---\nname: gh-stack\n---\n\n# gh-stack\n";
	const both = {
		".claude/skills/gh-stack/SKILL.md": skill,
		".agents/skills/gh-stack/SKILL.md": skill,
	};
	assert.deepEqual(analyse(snapshot(both), ["gh-stack"]), []);
	for (const half of Object.keys(both)) {
		assert.match(
			only(analyse(snapshot({ ...both, [half]: null }), ["gh-stack"])),
			/is missing, and gh-stack is listed as a skill Codex has/,
			half,
		);
	}
});

await test("a Codex skill with no Claude Code counterpart is reported", () => {
	assert.match(
		only(analyse(snapshot({ ".agents/skills/gh-stack/SKILL.md": "---\nname: gh-stack\n---\n" }))),
		/has no counterpart at \.claude\/skills\/gh-stack\/SKILL\.md/,
	);
});

await test("two pointers are allowed to look alike", () => {
	const failures = analyse(
		snapshot({ "server/AGENTS.md": "# Server\n", "server/CLAUDE.md": "@AGENTS.md\n" }),
	);
	assert.deepEqual(failures, []);
});

await test("failures arrive in the order analyse lists its checks", () => {
	// The module docstring promises this ordering; without a multi-failure case nothing observes it.
	const failures = analyse(
		snapshot({
			"server/AGENTS.md": { kind: "opaque" }, // unread
			"server/CLAUDE.md": "@AGENTS.md\n",
			"webapp/CLAUDE.md": null, // unreachable
			"CLAUDE.md": "@AGENTS.md\n@docs/missing.md\n", // dangling
			".opencode/commands/land-pr.md": null, // uncommanded
		}),
	);
	assert.deepEqual(
		failures.map((failure) => failure.split(" ").slice(0, 2).join(" ")),
		[
			"server/AGENTS.md was",
			"webapp/AGENTS.md is",
			"CLAUDE.md references",
			".claude/skills/land-pr/SKILL.md sets",
		],
	);
});
