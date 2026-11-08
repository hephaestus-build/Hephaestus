import { writeFile } from "node:fs/promises";

import { downloadReleaseAsset } from "./lib/release-asset.ts";

const [url, destination] = process.argv.slice(2);
if (
	url === undefined ||
	url === "" ||
	destination === undefined ||
	destination === "" ||
	process.argv.length !== 4 ||
	!url.startsWith("https://")
) {
	throw new Error("usage: node scripts/download-release-asset.ts <https-url> <destination>");
}
await writeFile(destination, await downloadReleaseAsset(url));
