import { appendFile, writeFile } from "node:fs/promises";

import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { resolveCiTool, toolCacheDirectory, toolCacheKey } from "./lib/ci-tool-resolver.ts";
import { requiredEnv } from "./lib/env.ts";

const id = requiredEnv(process.env, "CI_TOOL");
const variant = process.env.CI_TOOL_VARIANT ?? "";
const platform = `${process.platform}-${process.arch}`;
let selector = platform;
if (variant !== "") {
	selector = ["key", "checksums"].includes(variant) ? variant : `${platform}-${variant}`;
}
const pin = await ciToolPin(id, selector);
if (process.argv[2] === "file") {
	const destination = process.argv[3];
	if (destination === undefined || destination === "") {
		throw new Error("CI tool destination is required");
	}
	const resolved = await resolveCiTool(pin);
	await writeFile(destination, resolved.bytes);
} else {
	const output = requiredEnv(process.env, "GITHUB_OUTPUT");
	const values: Record<string, string> = {
		key: toolCacheKey(pin),
		directory: toolCacheDirectory(pin),
		version: pin.version,
		name: pin.name,
		sha256: pin.sha256,
	};
	if (process.argv[2] === "resolve") {
		const resolved = await resolveCiTool(pin);
		values.source = resolved.source;
		values.cacheable = String(resolved.cacheable);
		values.verified = "true";
	} else if (process.argv[2] !== "metadata") {
		throw new Error("usage: node scripts/ci-tool.ts metadata|resolve");
	}
	for (const [key, value] of Object.entries(values)) {
		if (/[\r\n]/u.test(value)) {
			throw new Error("Invalid CI tool output");
		}
		await appendFile(output, `${key}=${value}\n`);
	}
}
