/** Configure the Vite+ dispatcher during install without changing local hook preferences. */
import { execFileSync, spawnSync } from "node:child_process";
import path from "node:path";

import { CAPTURE_LIMIT_BYTES, repositoryCli } from "./lib/process.ts";

const HOOKS_DIR = ".vite-hooks";

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
 * Whether `core.hooksPath` names some checkout's dispatcher by an absolute path. Every worktree
 * shares that setting, and Git resolves only a relative one against the worktree it runs in, so an
 * absolute one runs a single checkout's dispatcher, hooks and launcher in all of them. Older Vite+
 * releases wrote it, and `vp config` keeps a path that is already set.
 */
export function namesAnotherDispatcher(configured: string): boolean {
	// The rule is the same on every host, whichever platform wrote the path.
	const absolute = path.posix.isAbsolute(configured) || path.win32.isAbsolute(configured);
	return absolute && /[\\/]\.vite-hooks[\\/]_[\\/]?$/u.test(configured);
}

// An image build or a source tarball has no work tree, and no hooks to enable.
if (import.meta.main && git("rev-parse", "--is-inside-work-tree") === "true") {
	// Unset rather than rewritten, so `vp config` keeps a `vp hooks disable` preference.
	if (namesAnotherDispatcher(git("config", "--local", "--get", "core.hooksPath"))) {
		git("config", "--local", "--unset", "core.hooksPath");
	}
	const enabled = spawnSync(
		process.execPath,
		[repositoryCli(), "config", "--no-agent", "--hooks-dir", HOOKS_DIR],
		{ stdio: "inherit" },
	);
	process.exitCode = enabled.status ?? 1;

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
