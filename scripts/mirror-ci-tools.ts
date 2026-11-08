import { allCiToolPins } from "./lib/ci-tool-catalog.ts";
import { publishCiToolGroup, type MirrorEntry } from "./lib/ci-tool-registry.ts";
import { resolveCiTool } from "./lib/ci-tool-resolver.ts";
import { fetchReleaseAssetApi, fetchReleaseAssetDirect } from "./lib/release-asset.ts";

const policy = { attempts: 4, timeoutMs: 60_000, delayMs: 5000 };
const groups = new Map<string, Awaited<ReturnType<typeof allCiToolPins>>>();
for (const pin of await allCiToolPins()) {
	const key = `${pin.tool}@${pin.version}`;
	const group = groups.get(key) ?? [];
	group.push(pin);
	groups.set(key, group);
}
const failed: string[] = [];
for (const [name, pins] of groups) {
	try {
		const entries: MirrorEntry[] = [];
		for (const pin of pins) {
			if (entries.some((entry) => entry.pin.sha256 === pin.sha256)) {
				continue;
			}
			const resolved = await resolveCiTool(pin, {
				sources: [
					{
						name: "github-api",
						read: async (subject) => fetchReleaseAssetApi(subject.url, policy),
					},
					{ name: "direct", read: async (subject) => fetchReleaseAssetDirect(subject.url, policy) },
				],
			});
			entries.push({ pin, bytes: resolved.bytes });
		}
		await publishCiToolGroup(entries, policy);
		console.info(`Mirrored CI tool ${name}: ${entries.length} verified assets`);
	} catch {
		failed.push(name);
		console.error(`CI tool mirror failed for ${name}; the existing tag was not replaced.`);
	}
}
if (failed.length > 0) {
	throw new Error(`CI tool mirror failed: ${failed.join(", ")}`);
}
