import { appendFile } from "node:fs/promises";
import path from "node:path";

import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { requiredEnv } from "./lib/env.ts";
import { installPinnedBinary, toolCache } from "./lib/pinned-binary.ts";

const pin = await ciToolPin("ripgrep");
const binary = await installPinnedBinary({
	cache: toolCache("ripgrep"),
	version: pin.version,
	asset: pin,
	url: new URL(".", pin.url).href,
	executable: "rg",
	member: pin.member,
});
await appendFile(requiredEnv(process.env, "GITHUB_PATH"), `${path.dirname(binary)}\n`);
