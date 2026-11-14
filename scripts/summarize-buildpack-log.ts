import { appendFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { stripVTControlCharacters } from "node:util";

import { isSet } from "./lib/env.ts";

// These describe archive eligibility, not application execution. Verification failures stay live:
// even an optional adapter investigated previously could fail for a different reason after an update.
const exclusion =
	/\[warning\]\[cds\] Skipping \S+: (?<reason>Unsupported location|Old class has been linked|Signed JAR|JFR event class)$/u;

// Every buildpack dependency comes from the dependency-mapping binding as a `file:` URI. Another
// download means that a pin is missing, and the build would depend on that host.
const download = /Downloading from (?<uri>\S+)/u;

// The lifecycle's own failure lines. Without an annotation, the step shows only its exit status,
// and the next step reports the missing digest instead of the cause.
const failure = /^(?:\[\w+\]\s+)?(?:ERROR: |unable to )/u;

const output = process.argv[2];
if (!isSet(output)) {
	throw new Error("Usage: summarize-buildpack-log.ts <raw-log-path>");
}
writeFileSync(output, "");
const counts = new Map<string, number>();
const downloads: string[] = [];
const failures: string[] = [];
for await (const line of createInterface({ input: process.stdin, crlfDelay: Infinity })) {
	appendFileSync(output, `${line}\n`);
	const plain = stripVTControlCharacters(line).trim();
	if (failure.test(plain)) {
		failures.push(plain);
	}
	const uri = download.exec(plain)?.groups?.uri;
	if (uri !== undefined && !uri.startsWith("file:")) {
		downloads.push(uri);
	}
	const reason = exclusion.exec(line)?.groups?.reason;
	if (reason === undefined) {
		console.log(line);
	} else {
		const count = counts.get(reason) ?? 0;
		// Show the first instance immediately, rather than disguising the warning's provenance.
		if (count === 0) {
			console.log(line);
		}
		counts.set(reason, count + 1);
	}
}
if (counts.size > 0) {
	console.log(
		"CDS archive diagnostics (log-line counts; all class names retained in the buildpacks-log artifact):",
	);
	for (const [reason, count] of counts) {
		console.log(`  ${reason}: ${count}`);
	}
}
for (const uri of downloads) {
	console.log(
		`::error::A buildpack downloaded ${uri}. Pin it in .github/ci-tools/buildpack-*.json with the buildpack's SHA-256.`,
	);
}
if (downloads.length > 0) {
	process.exitCode = 1;
}
// A line "unable to" alone can be a warning; the lifecycle prints "ERROR:" when the build fails.
if (failures.some((line) => line.includes("ERROR: "))) {
	// A workflow command ends at a line break, so the message escapes them (docs.github.com, workflow commands).
	const message = failures
		.join("\n")
		.replaceAll("%", "%25")
		.replaceAll("\r", "%0D")
		.replaceAll("\n", "%0A");
	console.log(`::error title=Buildpacks build failed::${message}`);
}
