import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { parseArgs } from "node:util";

import { run } from "./lib/process.ts";

/**
 * Runs one Maestro flow from `mobile/maestro/` against a build already installed on a booted
 * simulator or emulator. `smoke` needs no server; `journey` needs a Hephaestus with dev sign-in (the
 * `e2e` server profile) and a development-variant build. docs/contributor/mobile.mdx has the procedure.
 */

const FLOWS = ["smoke", "journey"] as const;
const PLATFORMS = ["ios", "android"] as const;

type Flow = (typeof FLOWS)[number];
type Platform = (typeof PLATFORMS)[number];

function oneOf<T extends string>(
	value: string | undefined,
	allowed: readonly T[],
	name: string,
): T {
	const match = allowed.find((candidate) => candidate === value);
	if (match === undefined) {
		throw new Error(`--${name} must be one of ${allowed.join(", ")}`);
	}
	return match;
}

const { values } = parseArgs({
	options: {
		platform: { type: "string" },
		flow: { type: "string", default: "smoke" },
		device: { type: "string" },
		"app-id": { type: "string", default: "build.hephaestus.app.dev" },
		"server-url": { type: "string", default: "http://localhost:8080" },
	},
	strict: true,
});

const platform: Platform = oneOf(values.platform, PLATFORMS, "platform");
const flow: Flow = oneOf(values.flow, FLOWS, "flow");
const serverUrl = new URL(values["server-url"]);
const deviceArgs = values.device === undefined ? [] : ["--device", values.device];

// The emulator's own localhost is not the host's; reversing the port lets one server address serve
// the flow, the auth browser and the app on both platforms.
if (platform === "android" && flow === "journey") {
	const adb =
		process.env.ANDROID_HOME === undefined
			? "adb"
			: path.join(process.env.ANDROID_HOME, "platform-tools", "adb");
	const port = serverUrl.port === "" ? "80" : serverUrl.port;
	const serial = values.device === undefined ? [] : ["-s", values.device];
	await run(adb, [...serial, "reverse", `tcp:${port}`, `tcp:${port}`]);
}

const results = path.join("mobile", "test-results");
await mkdir(results, { recursive: true });

const maestroArgs = [
	...deviceArgs,
	"--platform",
	platform,
	"test",
	"--env",
	`APP_ID=${values["app-id"]}`,
	"--env",
	`SERVER_URL=${serverUrl.origin}`,
	// A fresh account each run: the journey asserts first-sign-in consent and the empty workspace state.
	"--env",
	`USERNAME=mobile-e2e-${randomUUID().slice(0, 8)}`,
	"--format",
	"junit",
	"--output",
	path.join(results, `maestro-${platform}-${flow}.xml`),
	path.join("mobile", "maestro", `${flow}.yaml`),
];
await run("maestro", maestroArgs, { env: { MAESTRO_CLI_NO_ANALYTICS: "1" } });
