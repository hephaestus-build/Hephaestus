import { execFileSync } from "node:child_process";

import { verifyRevert } from "./verify-revert.ts";

/** Image contents and the Chrome extension, without tests and in-tree documentation. */
const SHIPPED_PATHS = [
	"server",
	"webapp",
	"docker",
	"extension",
	":!**/*.md",
	":!server/application/src/test",
	":!webapp/e2e",
	":!extension/e2e",
	":!**/*.test.ts",
	":!**/*.test.tsx",
	":!**/*.stories.tsx",
];

/**
 * Why a pull request's release notes break the rules that hold for every pull request, compared from
 * its merge base with `base`: no edit to MIGRATION.md, no deleted pending fragment or changeset, and
 * a changeset for each change to shipped code. CI and the pre-push hook run the same rules; CI exempts
 * drafts, bots and verified reverts.
 */
export function releaseNoteProblems(base: string, cwd = process.cwd()): string[] {
	const changed = (filter: string, ...paths: string[]) =>
		execFileSync(
			"git",
			["diff", `--diff-filter=${filter}`, "--name-only", `${base}...HEAD`, "--", ...paths],
			{
				cwd,
				encoding: "utf8",
				maxBuffer: 16 * 1024 * 1024,
			},
		)
			.split("\n")
			.filter((file) => file !== "" && !file.endsWith("README.md"));
	const problems: string[] = [];
	if (changed("ACDMRT", "MIGRATION.md").length > 0) {
		problems.push(
			"Do not edit MIGRATION.md in a feature PR; add a .migration/<changeset-slug>.md fragment instead.",
		);
	}
	const fragmentsRemoved = changed("DR", ".migration/*.md");
	if (fragmentsRemoved.length > 0) {
		problems.push(
			`Pending migration fragments may be edited, but not deleted or renamed: ${fragmentsRemoved.join(", ")}`,
		);
	}
	const changesetsRemoved = changed("DR", ".changeset/*.md");
	if (changesetsRemoved.length > 0) {
		problems.push(
			`Pending changesets may be edited or converted to explained empty changesets, but not deleted or renamed: ${changesetsRemoved.join(", ")}`,
		);
	}
	const shipped = changed("ACDMRT", ...SHIPPED_PATHS);
	if (shipped.length > 0 && changed("A", ".changeset/*.md").length === 0) {
		problems.push(
			`This PR changes shipped code but carries no changeset: ${shipped.slice(0, 5).join(", ")}${shipped.length > 5 ? ", …" : ""}`,
		);
	}
	return problems;
}

/** The release-note rules against `base`, as CI applies them; none for a verified revert or without a merge base. */
export function localReleaseNoteProblems(rootDir: string, target = "origin/main"): string[] {
	let base: string;
	try {
		base = execFileSync("git", ["merge-base", "HEAD", target], {
			cwd: rootDir,
			encoding: "utf8",
		}).trim();
	} catch {
		console.log(
			`No merge base with ${target}, so the release-note rules were not checked; CI checks them.`,
		);
		return [];
	}
	return verifyRevert(base, "HEAD", rootDir).verified ? [] : releaseNoteProblems(base, rootDir);
}
