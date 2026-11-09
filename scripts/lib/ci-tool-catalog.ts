import { readFile, readdir } from "node:fs/promises";

import { asRecord, asString, parseJson } from "./json.ts";

export interface CiToolPin {
	id: string;
	tool: string;
	version: string;
	platform: string;
	name: string;
	url: string;
	sha256: string;
	member?: string;
}

const directory = new URL("../../.github/ci-tools/", import.meta.url);

/** These files are the owning pins. Neither a resolver nor a mirror selects a newer release. */
async function specification(id: string) {
	if (!/^[a-z][a-z0-9-]*$/u.test(id)) {
		throw new Error("Invalid CI tool identifier");
	}
	const file =
		id === "vale"
			? new URL("../../.vale/toolchain.json", import.meta.url)
			: new URL(`${id}.json`, directory);
	return asRecord(parseJson(await readFile(file, "utf8")), `${id} pin`);
}

function selectPin(id: string, spec: Record<string, unknown>, platform: string): CiToolPin {
	const assets = asRecord(spec.assets, `${id} assets`);
	const asset = asRecord(assets[platform] ?? assets.all, `${id} asset for ${platform}`);
	const name = asString(asset.name, "CI tool asset name");
	const pin = {
		id,
		tool: spec.tool === undefined ? id : asString(spec.tool, "CI tool name"),
		version: asString(spec.version, "CI tool version"),
		platform,
		name,
		url:
			asset.url === undefined
				? new URL(name, asString(spec.url, "CI tool release URL")).href
				: asString(asset.url, "CI tool asset URL"),
		sha256: asString(asset.sha256, "CI tool SHA-256"),
		...(asset.member === undefined
			? {}
			: { member: asString(asset.member, "CI tool archive member") }),
	};
	if (
		!/^[a-z0-9-]+$/u.test(pin.tool) ||
		!/^[\w.-]+$/u.test(pin.version) ||
		!/^[a-f0-9]{64}$/u.test(pin.sha256) ||
		!/^[\w.+-]+$/u.test(name) ||
		!pin.url.startsWith("https://")
	) {
		throw new Error(`Invalid pin for ${id}`);
	}
	// An OCI tag cannot carry `+`, so a version such as Liberica's `21.0.13.0.3+5` is pinned with
	// `_`, as Helm writes it in OCI tags.
	const release = pin.version.replaceAll("_", "+");
	if (
		!pin.url.includes(`/v${release}/`) &&
		!pin.url.includes(`/${release}/`) &&
		!pin.url.includes(`-v${release}-`)
	) {
		throw new Error(`Release URL does not match the ${id} version`);
	}
	return pin;
}

export async function ciToolPin(
	id: string,
	platform = `${process.platform}-${process.arch}`,
): Promise<CiToolPin> {
	return selectPin(id, await specification(id), platform);
}

export async function allCiToolPins(): Promise<CiToolPin[]> {
	const entries = await readdir(directory);
	const ids = [
		"vale",
		...entries.filter((name) => name.endsWith(".json")).map((name) => name.slice(0, -5)),
	].toSorted();
	const pins: CiToolPin[] = [];
	for (const id of ids) {
		const spec = await specification(id);
		for (const platform of Object.keys(asRecord(spec.assets, "CI tool assets"))) {
			pins.push(selectPin(id, spec, platform));
		}
	}
	return pins;
}
