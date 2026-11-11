import { ciToolPin } from "./lib/ci-tool-catalog.ts";
import { installPinnedBinary, toolCache } from "./lib/pinned-binary.ts";
import { run } from "./lib/process.ts";

const pin = await ciToolPin("ripgrep");
const binary = await installPinnedBinary({
	cache: toolCache("ripgrep"),
	version: pin.version,
	asset: pin,
	url: new URL(".", pin.url).href,
	executable: "rg",
	member: pin.member,
});
// The tools server deliberately uses a fixed PATH, including /usr/local/bin.
await run("sudo", ["install", "-m", "0755", binary, "/usr/local/bin/rg"]);
await run("/usr/local/bin/rg", ["--version"]);
