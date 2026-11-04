/**
 * Points Git at the tracked hooks during install, and warns when this worktree's hooks would not run.
 *
 * `core.hooksPath` is shared by every worktree of a clone, and Git resolves a relative path in the
 * worktree that runs the hook. The tracked `.vite-hooks` exists in every checkout, so each worktree
 * runs its own hooks from its first checkout. The generated `.vite-hooks/_` dispatcher existed only
 * after an install in that worktree, and Git runs no hook, silently, when the directory is missing.
 */
import { execFileSync } from "node:child_process";
import { existsSync, realpathSync } from "node:fs";
import path from "node:path";

import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

export const HOOKS_DIR = ".vite-hooks";

const SIGNING_WARNING = `
warning: commits pushed to a branch in this repository must be signed, and this checkout is not
configured to sign. Sign with the SSH key you already push with:

  git config --global gpg.format ssh
  git config --global user.signingkey ~/.ssh/id_ed25519.pub
  git config --global commit.gpgsign true

Then register that public key on GitHub as a signing key — Settings > SSH and GPG keys > New SSH
key, key type "Signing Key", or: gh ssh-key add ~/.ssh/id_ed25519.pub --type signing

CONTRIBUTING.md, under "Signed Commits", has the rest.
`;

function git(...args: string[]): string {
	try {
		return execFileSync("git", args, {
			encoding: "utf8",
			stdio: ["ignore", "pipe", "ignore"],
			maxBuffer: CAPTURE_LIMIT_BYTES,
		}).trim();
	} catch {
		return "";
	}
}

/**
 * Whether `core.hooksPath` names this repository's hooks: the tracked directory or the dispatcher
 * that an install generates, relative, in one of `checkouts`, or in a checkout that no longer exists.
 * An absolute one runs one checkout's hooks and `vp` in every worktree, and a dispatcher exists only
 * after an install. A contributor's own hooks directory, even another project's `.vite-hooks`, is
 * neither.
 */
export function namesRepositoryHooks(configured: string, checkouts: readonly string[]): boolean {
	// The rule is the same on every host, whichever platform wrote the path.
	const match = /^(?:(?<checkout>.+)[\\/])?\.vite-hooks(?:[\\/]_)?[\\/]?$/u.exec(configured);
	if (match === null) {
		return false;
	}
	const checkout = match.groups?.checkout;
	if (checkout === undefined) {
		return true;
	}
	if (!path.isAbsolute(checkout) && !path.win32.isAbsolute(checkout)) {
		return false;
	}
	return (
		!existsSync(checkout) ||
		checkouts.some((tree) => existsSync(tree) && realpathSync(tree) === realpathSync(checkout))
	);
}

/** The paths of the worktrees of this clone that still exist. */
function worktrees(): string[] {
	return git("worktree", "list", "--porcelain")
		.split("\n")
		.flatMap((line) => (line.startsWith("worktree ") ? [line.slice("worktree ".length)] : []))
		.filter((tree) => existsSync(tree));
}

/** The hooks directory that Git uses in `worktree`, or undefined when it uses `.git/hooks`. */
export function effectiveHooksPath(configured: string, worktree: string): string | undefined {
	if (configured === "") {
		return undefined;
	}
	return path.isAbsolute(configured) ? configured : path.join(worktree, configured);
}

/**
 * A warning when Git would run no repository hook in this worktree, because the hooks directory it
 * uses does not exist. A per-worktree or global setting can outrank the shared one, so this judges
 * the path Git resolves here.
 */
export function missingHooksWarning(): string | undefined {
	const worktree = git("rev-parse", "--show-toplevel");
	const hooks = effectiveHooksPath(
		git("config", "--type=path", "--get", "core.hooksPath"),
		worktree,
	);
	if (worktree === "" || (hooks !== undefined && existsSync(hooks))) {
		return undefined;
	}
	if (hooks === undefined) {
		return `\nwarning: Git runs no repository hook in this worktree: core.hooksPath is not set.\nRun \`pnpm install --frozen-lockfile\`, which sets it to ${HOOKS_DIR}.\n\n`;
	}
	return `\nwarning: Git runs no repository hook in this worktree: core.hooksPath resolves to ${hooks}, which does not exist.\nRemove the override with \`git config --worktree --unset core.hooksPath\`, or point it at ${path.join(worktree, HOOKS_DIR)}.\n\n`;
}

// An image build or a source tarball has no work tree, and no hooks to enable.
if (import.meta.main && git("rev-parse", "--is-inside-work-tree") === "true") {
	const trees = worktrees();
	const shared = git("config", "--local", "--get", "core.hooksPath");
	const effective = git("config", "--type=path", "--get", "core.hooksPath");
	// A contributor's own hooks directory, local or global, stays; a path to this repository's hooks
	// in one checkout becomes the relative one, which each worktree resolves to its own.
	if (
		shared !== HOOKS_DIR &&
		(namesRepositoryHooks(shared, trees) ||
			(shared === "" && (effective === "" || namesRepositoryHooks(effective, trees))))
	) {
		git("config", "--local", "core.hooksPath", HOOKS_DIR);
	}
	// A per-worktree override outranks the shared path; drop every one that names this repository's
	// hooks, so no worktree depends on which checkout last ran an install.
	for (const tree of trees) {
		const file = git(
			"-C",
			tree,
			"rev-parse",
			"--path-format=absolute",
			"--git-path",
			"config.worktree",
		);
		if (
			file !== "" &&
			existsSync(file) &&
			namesRepositoryHooks(git("config", "--file", file, "--get", "core.hooksPath"), trees)
		) {
			git("config", "--file", file, "--unset", "core.hooksPath");
		}
	}
	const warning = missingHooksWarning();
	if (warning !== undefined) {
		process.stderr.write(warning);
	}

	// Signing remains a warning so a checkout without a key can still build.
	if (process.env.CI !== "true") {
		const signs =
			git("config", "--get", "--type=bool", "commit.gpgsign") === "true" &&
			git("config", "--get", "user.signingkey") !== "";
		if (!signs) {
			process.stderr.write(SIGNING_WARNING);
		}
	}
}
