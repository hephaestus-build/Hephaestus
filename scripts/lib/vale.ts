import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { chmod, cp, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { unzipSync } from "fflate";
import { stringify } from "yaml";

import { asArray, asRecord, asString, parseJson } from "./json.ts";
import { CAPTURE_LIMIT_BYTES } from "./process.ts";
import { approvedWords, productTerms, steRoot, substitutions, contractions } from "./ste-words.ts";

const root = fileURLToPath(steRoot);
const toolchain = asRecord(
	parseJson(await readFile(new URL(".vale/toolchain.json", steRoot), "utf8")),
	"Vale toolchain",
);

export function assetFor(platform: string, arch: string) {
	const assets = asRecord(toolchain.assets, "Vale assets");
	const asset = asRecord(assets[`${platform}-${arch}`], `Vale asset for ${platform}-${arch}`);
	return {
		name: asString(asset.name, "asset name"),
		sha256: asString(asset.sha256, "asset SHA-256"),
	};
}

export function verifyArchive(bytes: Uint8Array, sha256: string): void {
	if (createHash("sha256").update(bytes).digest("hex") !== sha256) {
		throw new Error(
			"Vale archive checksum differs. Remove the cache file, then download the pinned archive again.",
		);
	}
}

/** Only the named executable leaves the verified archive; no archive path is extracted to disk. */
export function executableFromArchive(bytes: Uint8Array, windows: boolean): Uint8Array {
	if (windows) {
		const executable = unzipSync(bytes, { filter: (entry) => entry.name === "vale.exe" })[
			"vale.exe"
		];
		if (executable === undefined) {
			throw new Error("Vale archive has no vale.exe. Use the pinned Windows archive.");
		}
		return executable;
	}
	return execFileSync("tar", ["-xzOf", "-", "vale"], {
		input: bytes,
		maxBuffer: CAPTURE_LIMIT_BYTES,
	});
}

export async function prepareVale(): Promise<{ binary: string; dispose: () => Promise<void> }> {
	const asset = assetFor(process.platform, process.arch);
	const cache = path.join(root, ".cache", "ste");
	await mkdir(cache, { recursive: true });
	const archive = path.join(cache, `${asset.sha256}-${asset.name}`);
	let bytes = await readFile(archive).catch(() => undefined);
	if (bytes === undefined) {
		const response = await fetch(`${asString(toolchain.url, "release URL")}${asset.name}`, {
			signal: AbortSignal.timeout(120_000),
		});
		if (!response.ok) {
			throw new Error(`Vale download returned ${response.status}. Retry the pinned release URL.`);
		}
		bytes = Buffer.from(await response.arrayBuffer());
		verifyArchive(bytes, asset.sha256);
		const temporary = await mkdtemp(path.join(cache, "download-"));
		try {
			const file = path.join(temporary, asset.name);
			await writeFile(file, bytes);
			await rename(file, archive);
		} finally {
			await rm(temporary, { recursive: true, force: true });
		}
	}
	verifyArchive(bytes, asset.sha256);
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-vale-"));
	const dispose = async () => {
		await rm(directory, { recursive: true, force: true });
	};
	try {
		const binary = path.join(directory, process.platform === "win32" ? "vale.exe" : "vale");
		await writeFile(binary, executableFromArchive(bytes, process.platform === "win32"));
		await chmod(binary, 0o700);
		const version = execFileSync(binary, ["--version"], { encoding: "utf8" }).trim();
		if (version !== `vale version ${asString(toolchain.version, "Vale version")}`) {
			throw new Error(`Unexpected Vale version: ${version}. Use the pinned archive.`);
		}
		await generateWordStyles(directory);
		return { binary, dispose };
	} catch (error) {
		await dispose();
		throw error;
	}
}

async function generateWordStyles(directory: string): Promise<void> {
	const styles = path.join(directory, "styles");
	const configuration = await readFile(path.join(root, ".vale.ini"), "utf8");
	await writeFile(
		path.join(directory, "vale.ini"),
		configuration.replace(
			"StylesPath = .vale/styles",
			`StylesPath = ${styles.split(path.sep).join("/")}`,
		),
	);
	await cp(path.join(root, ".vale", "styles"), styles, { recursive: true, force: true });
	await mkdir(path.join(styles, "config", "dictionaries"), { recursive: true });
	const technicalWords = productTerms.flatMap((term) => term.split(/[^\p{L}]+/u)).filter(Boolean);
	const dictionary = [...new Set([...approvedWords, ...technicalWords])].toSorted();
	await writeFile(
		path.join(styles, "config", "dictionaries", "ste.dic"),
		`${dictionary.length}\n${dictionary.join("\n")}\n`,
	);
	await writeFile(path.join(styles, "config", "dictionaries", "ste.aff"), "SET UTF-8\n");
	await writeFile(
		path.join(styles, "STE", "Vocabulary.yml"),
		stringify({
			extends: "spelling",
			level: "suggestion",
			append: false,
			dictionaries: ["ste"],
			message:
				'Write an approved word instead of "%s", or use a technical name or verb from the product vocabulary.',
		}),
	);
	await writeFile(
		path.join(styles, "STE", "Contractions.yml"),
		stringify({
			extends: "substitution",
			level: "error",
			ignorecase: true,
			message: 'Write "%s" instead of "%s". Choose the full form that keeps the meaning.',
			swap: Object.fromEntries(contractions.map(({ from, to }) => [from, to])),
		}),
	);
	await writeFile(
		path.join(styles, "STE", "Words.yml"),
		stringify({
			extends: "substitution",
			level: "error",
			ignorecase: true,
			message: 'Write "%s" instead of "%s". Keep the same meaning.',
			swap: Object.fromEntries(substitutions.map(({ from, to }) => [from, to])),
		}),
	);
}

export interface ValeAlert {
	Check: string;
	Severity: string;
	Message: string;
	Line: number;
}

export function valeAlerts(
	binary: string,
	files: string[],
	level = "suggestion",
): Map<string, ValeAlert[]> {
	const environment = { ...process.env };
	for (const key of Object.keys(environment)) {
		if (key.startsWith("VALE_") || key === "DICPATH") {
			environment[key] = undefined;
		}
	}
	const result = spawnSync(
		binary,
		[
			"--config",
			path.join(path.dirname(binary), "vale.ini"),
			"--output=JSON",
			`--minAlertLevel=${level}`,
			...files,
		],
		{
			cwd: root,
			encoding: "utf8",
			env: environment,
			maxBuffer: CAPTURE_LIMIT_BYTES,
			timeout: 120_000,
		},
	);
	if (result.error !== undefined || (result.status !== 0 && result.status !== 1)) {
		throw new Error(`Vale did not finish. ${result.error?.message ?? result.stderr}`);
	}
	const alerts = parseValeAlerts(result.stdout);
	// Vale uses exit 1 for prose errors and for setup errors. Only structured alerts prove a lint run.
	if (result.status === 1 && alerts.size === 0) {
		throw new Error(`Vale returned no alerts. Fix its configuration. ${result.stderr}`);
	}
	return alerts;
}

export function parseValeAlerts(source: string): Map<string, ValeAlert[]> {
	const parsed = asRecord(parseJson(source), "Vale result");
	return new Map(
		Object.entries(parsed).map(([file, alerts]) => [
			file.replaceAll("\\", "/"),
			asArray(alerts, file).map((value) => {
				const alert = asRecord(value, "Vale alert");
				if (typeof alert.Line !== "number") {
					throw new Error("Vale alert has no line. Check the pinned output contract.");
				}
				return {
					Check: asString(alert.Check, "check"),
					Severity: asString(alert.Severity, "severity"),
					Message: asString(alert.Message, "message"),
					Line: alert.Line,
				};
			}),
		]),
	);
}
