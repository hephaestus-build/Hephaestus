import { randomBytes } from "node:crypto";
import { lstat, mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import type { CiToolPin } from "./ci-tool-catalog.ts";
import { matchesToolPin } from "./ci-tool-integrity.ts";
import { fetchMirroredCiTool } from "./ci-tool-registry.ts";
import { downloadFailure, fetchReleaseAssetApi, fetchReleaseAssetDirect } from "./release-asset.ts";

export type ToolSubject = Pick<CiToolPin, "tool" | "version" | "sha256" | "url" | "name">;
export interface ToolSource {
	name: "ghcr" | "github-api" | "direct";
	read: (pin: ToolSubject) => Promise<Uint8Array>;
}

export function toolCacheKey(pin: ToolSubject): string {
	if (
		!/^[\w-]+$/u.test(pin.tool) ||
		!/^[\w.-]+$/u.test(pin.version) ||
		!/^[a-f0-9]{64}$/u.test(pin.sha256)
	) {
		throw new Error("Invalid pinned CI tool identity");
	}
	return `ci-tool-v1-${pin.tool}-${pin.version}-${pin.sha256}`;
}

export function toolCacheDirectory(
	pin: ToolSubject,
	root = process.env.CI_TOOL_CACHE_DIR ??
		path.join(process.env.RUNNER_TEMP ?? tmpdir(), "hephaestus-ci-tools"),
): string {
	return path.join(root, toolCacheKey(pin));
}

async function saveVerifiedBytes(
	directory: string,
	file: string,
	bytes: Uint8Array,
): Promise<boolean> {
	let temporary: string | undefined;
	try {
		await mkdir(directory, { recursive: true });
		const info = await lstat(directory);
		if (!info.isDirectory()) {
			return false;
		}
		temporary = path.join(directory, `asset-${randomBytes(8).toString("hex")}.tmp`);
		await writeFile(temporary, bytes, { mode: 0o600, flag: "wx" });
		await rename(temporary, file);
		return true;
	} catch {
		return false;
	} finally {
		if (temporary !== undefined) {
			await rm(temporary, { force: true }).catch(() => undefined);
		}
	}
}

/** Every source is untrusted until its bytes match the repository's SHA-256 pin. */
export async function resolveCiTool(
	pin: ToolSubject,
	options: {
		cacheRoot?: string;
		offline?: boolean;
		sources?: readonly ToolSource[];
		log?: (message: string) => void;
	} = {},
) {
	const directory = toolCacheDirectory(pin, options.cacheRoot);
	const file = path.join(directory, "asset");
	const log = options.log ?? console.info;
	const failures: string[] = [];
	try {
		const cachedDirectory = await lstat(directory);
		if (!cachedDirectory.isDirectory()) {
			throw new Error("Not a regular cache directory");
		}
		const cachedFile = await lstat(file);
		if (!cachedFile.isFile()) {
			throw new Error("Not a regular cache file");
		}
		const bytes = await readFile(file);
		if (matchesToolPin(bytes, pin)) {
			log(`CI tool ${pin.tool}@${pin.version}: source=cache sha256=${pin.sha256}`);
			return { bytes, source: "cache", file, cacheable: true };
		}
		failures.push("cache: checksum mismatch");
		log(`CI tool ${pin.tool}@${pin.version}: refused cache checksum mismatch`);
	} catch {
		failures.push("cache: unavailable");
	}
	await rm(directory, { recursive: true, force: true }).catch(() => undefined);
	const sources =
		options.offline === true
			? []
			: (options.sources ??
				([
					{ name: "ghcr", read: fetchMirroredCiTool },
					{ name: "github-api", read: async (subject) => fetchReleaseAssetApi(subject.url) },
					{ name: "direct", read: async (subject) => fetchReleaseAssetDirect(subject.url) },
				] satisfies readonly ToolSource[]));
	for (const source of sources) {
		try {
			const bytes = await source.read(pin);
			if (!matchesToolPin(bytes, pin)) {
				failures.push(`${source.name}: checksum mismatch`);
				log(`CI tool ${pin.tool}@${pin.version}: refused ${source.name} checksum mismatch`);
				continue;
			}
			const cacheable = await saveVerifiedBytes(directory, file, bytes);
			if (!cacheable) {
				log(`CI tool ${pin.tool}@${pin.version}: verified source could not be cached`);
			}
			log(`CI tool ${pin.tool}@${pin.version}: source=${source.name} sha256=${pin.sha256}`);
			return { bytes, source: source.name, file, cacheable };
		} catch (error) {
			const reason = downloadFailure(error);
			failures.push(`${source.name}: ${reason}`);
			log(`CI tool ${pin.tool}@${pin.version}: ${source.name} unavailable (${reason})`);
		}
	}
	throw new Error(
		`No verified source for CI tool ${pin.tool}@${pin.version}, sha256=${pin.sha256}. ${failures.join("; ")}`,
	);
}
