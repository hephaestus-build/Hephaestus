/**
 * Installs one executable from a release archive that the repository pins by SHA-256. The cache keeps
 * the archive beside the executable, and each use verifies both again before it runs anything.
 */
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { unzipSync } from "fflate";

import { environmentWithoutGitRepository } from "./git-environment.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";
import { PUSH_CHECK_ENV } from "./push-gates.ts";
import { downloadReleaseAsset } from "./release-asset.ts";

/**
 * The cache directory for one pinned tool, shared by every worktree of the clone. Each use verifies
 * the archive and the executable against the pin, so a copy that another worktree installed is as
 * trustworthy as a fresh download. The push check does not download, so a per-worktree cache would
 * fail every new worktree's first push. Outside a Git checkout the cache stays in the tree.
 */
export function toolCache(name: string, cwd = import.meta.dirname): string {
	try {
		const common = execFileSync(
			"git",
			["rev-parse", "--path-format=absolute", "--git-common-dir"],
			{ cwd, encoding: "utf8", env: environmentWithoutGitRepository() },
		).trim();
		return path.join(common, "hephaestus-tools", name);
	} catch {
		return path.join(import.meta.dirname, "..", "..", ".cache", name);
	}
}

export interface PinnedAsset {
	readonly name: string;
	readonly sha256: string;
}

export function verifyArchive(bytes: Uint8Array, asset: PinnedAsset): void {
	if (createHash("sha256").update(bytes).digest("hex") !== asset.sha256) {
		throw new Error(
			`${asset.name} checksum differs. Remove the cache file, then download the pinned archive again.`,
		);
	}
}

/** Only the named executable leaves the verified archive; no archive path is extracted to disk. */
export function executableFromArchive(
	bytes: Uint8Array,
	archive: string,
	executable: string,
): Uint8Array {
	if (archive.endsWith(".zip")) {
		const file = unzipSync(bytes, { filter: (entry) => entry.name === executable })[executable];
		if (file === undefined) {
			throw new Error(`${archive} has no ${executable}. Use the pinned archive.`);
		}
		return file;
	}
	return execFileSync("tar", ["-xzOf", "-", executable], {
		input: bytes,
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
}

export async function installPinnedBinary(options: {
	cache: string;
	version: string;
	asset: PinnedAsset;
	/** The release download directory, with a trailing `/`. */
	url: string;
	/** The installed file, such as `vale.exe`. */
	executable: string;
	/** The archive member of the executable, when it is not `executable`. */
	member?: string;
	/** Fixture installers can allow their local HTTP server during a push check. */
	allowDownload?: boolean;
}): Promise<string> {
	const { cache, version, asset, url, executable } = options;
	const member = options.member ?? executable;
	const directory = path.join(cache, `${path.parse(executable).name}-${version}-${asset.sha256}`);
	const binary = path.join(directory, executable);
	const archive = path.join(directory, asset.name);
	const verifyInstallation = async () => {
		const bytes = await readFile(archive);
		verifyArchive(bytes, asset);
		const expected = executableFromArchive(bytes, asset.name, member);
		const installed = await readFile(binary);
		if (!installed.equals(expected)) {
			throw new Error(
				`The ${executable} executable differs from the pinned archive. Remove its cache directory.`,
			);
		}
	};

	try {
		await verifyInstallation();
		return binary;
	} catch (error) {
		if (!(error instanceof Error && "code" in error && error.code === "ENOENT")) {
			throw error;
		}
	}

	if (!(options.allowDownload ?? process.env[PUSH_CHECK_ENV] !== "true")) {
		throw new Error(
			`${executable} is not cached. Run \`vp run check\` to install the pinned tools before push checks.`,
		);
	}
	const bytes = await downloadReleaseAsset(`${url}${asset.name}`);
	verifyArchive(bytes, asset);
	const contents = executableFromArchive(bytes, asset.name, member);
	await mkdir(cache, { recursive: true });
	const temporary = await mkdtemp(path.join(cache, "install-"));
	try {
		await writeFile(path.join(temporary, asset.name), bytes);
		await writeFile(path.join(temporary, executable), contents, { mode: 0o700 });
		// A nonempty directory cannot be replaced. No staging executable is run.
		try {
			await rename(temporary, directory);
		} catch (error) {
			try {
				await verifyInstallation();
			} catch {
				throw error;
			}
		}
	} finally {
		await rm(temporary, { recursive: true, force: true });
	}
	return binary;
}
