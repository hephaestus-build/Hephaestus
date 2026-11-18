/**
 * The build gate's entry to `lib/jvm-runtime.ts`: appends the JVM findings of one image digest to its
 * Trivy report. `--pinned` marks an image built from this checkout.
 */
import { parseArgs } from "node:util";

import { scanJvmRuntime } from "./lib/jvm-runtime.ts";

if (import.meta.main) {
	const { positionals, values } = parseArgs({
		options: { pinned: { type: "boolean" } },
		allowPositionals: true,
	});
	const [image, platform, reference, report] = positionals;
	if (
		image === undefined ||
		platform === undefined ||
		reference === undefined ||
		report === undefined
	) {
		throw new Error(
			"usage: scan-jvm-runtime <image> <platform> <reference> <trivy.json> [--pinned]",
		);
	}
	const packages = await scanJvmRuntime({
		image,
		platform,
		reference,
		report,
		pinned: values.pinned === true,
	});
	process.stdout.write(
		`${image} (${platform}): ${packages.length === 0 ? "no JVM" : packages.map((jvm) => `${jvm.name} ${jvm.version}`).join(", ")}\n`,
	);
}
