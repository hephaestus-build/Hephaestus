/**
 * Writes the server image's build descriptor: `server/application/project.toml` with the JVM buildpack that
 * `.github/ci-tools/buildpack-jre.json` names. Every `pack build` of the server reads this file, not
 * `project.toml`, so the JRE pin alone chooses the JVM vendor.
 *
 * Usage: node scripts/server-descriptor.ts <project.toml> <output>
 */
import { writeFileSync } from "node:fs";

import { serverDescriptor } from "./lib/buildpack-descriptor.ts";
import { isSet } from "./lib/env.ts";

const [descriptor, output] = process.argv.slice(2);
if (!isSet(descriptor) || !isSet(output)) {
	throw new Error("usage: server-descriptor <project.toml> <output>");
}
writeFileSync(output, serverDescriptor(descriptor));
