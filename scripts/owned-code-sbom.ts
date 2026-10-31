import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { lstatSync, readFileSync, readlinkSync } from "node:fs";
import path from "node:path";
import { isDeepStrictEqual } from "node:util";

import npa from "npm-package-arg";

import { isSet } from "./lib/env.ts";
import { environmentWithoutGitRepository } from "./lib/git-environment.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES, exitStatus } from "./lib/process.ts";

const roots = ["webapp/src", "extension/src", "docker", "server/application/src/main"];
export const OWNED_ASSET_ROOTS = ["webapp/public", "extension/public", "docker/agents/pi/patches"];
export const OWNED_FIELDS = ["Owned from", "Package", "License", "Changes"];

export function isShippedFile(file: string): boolean {
	return (
		roots.some((directory) => file.startsWith(`${directory}/`)) &&
		!OWNED_ASSET_ROOTS.some((directory) => file.startsWith(`${directory}/`)) &&
		!/(?:^|\/)(?:test|__tests__)\//u.test(file) &&
		!/\.(?:test|spec|stories)\./u.test(file)
	);
}

function upstreamPackage(purl: string) {
	const match =
		/^pkg:(?<type>[a-z][a-z0-9.+-]*)\/(?<encodedName>[^@\s?#]+)@(?<encodedVersion>[^@/\s?#]+)(?:\?[^#\s]+)?(?:#[^\s]+)?$/u.exec(
			purl,
		);
	if (!match) {
		throw new Error("Package must be a versioned package URL");
	}
	const type = asString(match.groups?.type, "purl type");
	const encodedName = asString(match.groups?.encodedName, "purl name");
	const encodedVersion = asString(match.groups?.encodedVersion, "purl version");
	const name = decodeURIComponent(encodedName);
	const version = decodeURIComponent(encodedVersion);
	if (type === "npm") {
		const parsed = npa.resolve(name, version);
		if (
			parsed.type !== "version" ||
			parsed.fetchSpec !== version ||
			`pkg:npm/${encodeURIComponent(name).replaceAll("%2F", "/")}@${version}` !== purl
		) {
			throw new Error("Package must be a canonical versioned npm purl (encode a scope's @ as %40)");
		}
	}
	return { type: "library", name, version, purl };
}

/** The header format belongs to docs/contributor/dependencies.md. */
export function ownedComponent(file: string, input: string | Uint8Array) {
	if (!isShippedFile(file)) {
		return;
	}
	const bytes = typeof input === "string" ? Buffer.from(input) : input;
	let content: string;
	try {
		content = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
	} catch (error) {
		throw new Error(`${file}: owned source must be UTF-8 text`, { cause: error });
	}
	if (
		!content
			.split(/\r?\n/u)
			.slice(0, 40)
			.some((line) => /owned[ \t]*from:/iu.test(line))
	) {
		return;
	}
	if (content.includes("\0")) {
		throw new Error(`${file}: owned source must be text`);
	}
	const comment = (
		file.endsWith(".css")
			? /^\s*\/\*(?<comment>[\s\S]*?)\*\//u
			: /^\s*\/\*\*(?<comment>[\s\S]*?)\*\//u
	).exec(content)?.groups?.comment;
	if (comment === undefined) {
		throw new Error(`${file}: owned code must start with its header comment`);
	}
	return ownedFileComponent(file, bytes, ownedHeaderValues(file, comment, OWNED_FIELDS));
}

export function ownedHeaderValues(file: string, comment: string, names: readonly string[]) {
	const entries = [
		...comment.matchAll(
			new RegExp(`^[\\t ]*\\*?[\\t ]*(?<field>${names.join("|")}):[\\t ]*(?<value>.*)$`, "gmu"),
		),
	];
	if (
		entries.length !== names.length ||
		entries.some((entry, index) => entry.groups?.field !== names[index])
	) {
		throw new Error(`${file}: owned header needs each field once, in order: ${names.join(", ")}`);
	}
	return entries.map((entry) => entry.groups?.value?.trim());
}

export function ownedFileComponent(
	file: string,
	bytes: Uint8Array,
	values: readonly (string | undefined)[],
) {
	const [source, purl, license, changes] = values;
	if (!isSet(source) || !isSet(purl) || !isSet(license) || !isSet(changes)) {
		throw new Error(`${file}: owned header fields must be nonempty; Owned from must use HTTPS`);
	}
	try {
		if (new URL(source).protocol !== "https:") {
			throw new Error("Owned from must use HTTPS");
		}

		const digest = createHash("sha256").update(bytes).digest("hex");
		return {
			type: "file",
			"bom-ref": `owned:${file}`,
			name: file,
			hashes: [{ alg: "SHA-256", content: digest }],
			licenses: [{ expression: license }],
			pedigree: {
				ancestors: [
					{ ...upstreamPackage(purl), externalReferences: [{ type: "vcs", url: source }] },
				],
				notes: changes,
			},
		};
	} catch (error) {
		throw new Error(`${file}: ${error instanceof Error ? error.message : String(error)}`, {
			cause: error,
		});
	}
}

export type OwnedComponent = ReturnType<typeof ownedFileComponent>;

/** Read the committed release or the working tree, including files that have not been staged yet. */
export function ownedCandidates(
	paths: readonly string[],
	commit: string | undefined,
	directory: string,
): string[] {
	const result = spawnSync(
		"git",
		[
			"grep",
			"--text",
			"--ignore-case",
			"--extended-regexp",
			"-l",
			"-z",
			...(commit === undefined ? ["--untracked", "--exclude-standard"] : []),
			"-e",
			"owned[ \t]*from:",
			...(commit === undefined ? [] : [commit]),
			"--",
			...paths,
		],
		{
			cwd: directory,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			env: environmentWithoutGitRepository(),
		},
	);
	if (result.error) {
		throw result.error;
	}
	if (result.status !== 0 && result.status !== 1) {
		throw new Error(
			`owned-code file discovery failed: ${exitStatus(result.status, result.signal)}`,
		);
	}
	return result.stdout
		.split("\0")
		.filter(Boolean)
		.toSorted()
		.map((entry) => (commit === undefined ? entry : entry.slice(commit.length + 1)));
}

function git(args: readonly string[], directory: string, failure: string): string {
	const result = spawnSync("git", args, {
		cwd: directory,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: environmentWithoutGitRepository(),
	});
	if (result.error || result.status !== 0) {
		throw new Error(`${failure}: ${exitStatus(result.status, result.signal)}`, {
			cause: result.error,
		});
	}
	return result.stdout;
}

/** The scan reads each path's own bytes, so a link out of the roots would ship a file it never reads. */
export function assertLinksStayInRoots(
	paths: readonly string[],
	commit: string | undefined,
	directory: string,
): void {
	const links: [string, string][] = [];
	if (commit === undefined) {
		const files = git(
			["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", ...paths],
			directory,
			"owned-code link discovery failed",
		);
		for (const file of files.split("\0").filter(Boolean)) {
			const absolute = path.join(directory, file);
			if (lstatSync(absolute, { throwIfNoEntry: false })?.isSymbolicLink()) {
				links.push([file, readlinkSync(absolute)]);
			}
		}
	} else {
		const tree = git(
			["ls-tree", "-r", "-z", commit, "--", ...paths],
			directory,
			"owned-code link discovery failed",
		);
		for (const entry of tree.split("\0").filter(Boolean)) {
			const [meta = "", file = ""] = entry.split("\t");
			const [mode, , object = ""] = meta.split(" ");
			if (mode === "120000") {
				links.push([file, git(["cat-file", "blob", object], directory, `cannot read ${file}`)]);
			}
		}
	}
	const targets = new Map(links);
	const inside = (file: string) =>
		roots.some((root) => file === root || file.startsWith(`${root}/`));
	const above = (directory: string) => roots.some((root) => root.startsWith(`${directory}/`));
	// The OS follows a directory link before it applies a later `..`, so resolve one segment at a time.
	const resolve = (link: string, hops: number): string | undefined => {
		const target = targets.get(link) ?? "";
		if (hops > 40 || path.posix.isAbsolute(target)) {
			return undefined;
		}
		let parts = path.posix.dirname(link).split("/");
		for (const segment of target.split("/")) {
			if (segment === "" || segment === ".") {
				continue;
			}
			if (segment === "..") {
				if (parts.pop() === undefined) {
					return undefined;
				}
				continue;
			}
			const current = [...parts, segment].join("/");
			if (targets.has(current)) {
				const followed = resolve(current, hops + 1);
				if (followed === undefined) {
					return undefined;
				}
				parts = followed.split("/");
			} else if (inside(current) || above(current)) {
				parts.push(segment);
			} else {
				return undefined;
			}
		}
		const resolved = parts.join("/");
		return inside(resolved) ? resolved : undefined;
	};
	for (const [file] of links) {
		if (resolve(file, 0) === undefined) {
			throw new Error(`${file}: a link in shipped code must point inside ${roots.join(", ")}`);
		}
	}
}

export function readOwnedFile(
	file: string,
	commit: string | undefined,
	directory: string,
): Uint8Array {
	if (commit === undefined) {
		return readFileSync(path.join(directory, file));
	}
	const captured = spawnSync("git", ["show", `${commit}:${file}`], {
		cwd: directory,
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: environmentWithoutGitRepository(),
	});
	if (captured.error || captured.status !== 0) {
		throw new Error(`cannot read owned source ${file} at ${commit}`, { cause: captured.error });
	}
	return captured.stdout;
}

export function scanOwnedCode(commit?: string, directory = process.cwd()): OwnedComponent[] {
	assertLinksStayInRoots(roots, commit, directory);
	const files = ownedCandidates(roots, commit, directory);
	const components: OwnedComponent[] = [];
	for (const file of files) {
		if (!isShippedFile(file)) {
			continue;
		}
		const content = readOwnedFile(file, commit, directory);
		const component = ownedComponent(file, content);
		if (component !== undefined) {
			components.push(component);
		}
	}
	return components;
}

export function assertOwnedCoverage(cycloneDx: unknown, expected: readonly OwnedComponent[]): void {
	const components = asArray(
		asRecord(cycloneDx, "CycloneDX SBOM").components,
		"CycloneDX components",
	);
	for (const component of expected) {
		const matches = components.filter(
			(entry) => asRecord(entry, "component")["bom-ref"] === component["bom-ref"],
		);
		if (matches.length !== 1 || !isDeepStrictEqual(matches[0], component)) {
			throw new Error(`release SBOM omits or changes the owned-code entry for ${component.name}`);
		}
	}
	const expectedRefs = new Set(expected.map((component) => component["bom-ref"]));
	for (const entry of components) {
		const reference = asRecord(entry, "component")["bom-ref"];
		if (
			typeof reference === "string" &&
			reference.startsWith("owned:") &&
			!expectedRefs.has(reference)
		) {
			throw new Error(`release SBOM has an unexpected owned-code entry: ${reference}`);
		}
	}
}

export function addOwnedComponents(cycloneDx: unknown, owned: readonly OwnedComponent[]) {
	const bom = asRecord(cycloneDx, "CycloneDX SBOM");
	const enriched = {
		...bom,
		components: [...asArray(bom.components, "CycloneDX components"), ...owned],
	};
	return enriched;
}

if (import.meta.main) {
	const owned = scanOwnedCode();
	console.log(`owned-code SBOM: ${owned.length} shipped files have complete headers`);
}
