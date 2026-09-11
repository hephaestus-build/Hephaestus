import { appendFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";

// These describe archive eligibility, not application execution. Verification failures stay live:
// even an optional adapter investigated previously could fail for a different reason after an update.
const exclusion =
	/\[warning\]\[cds\] Skipping \S+: (Unsupported location|Old class has been linked|Signed JAR|JFR event class)$/;

const output = process.argv[2];
if (!output) throw new Error("Usage: summarize-buildpack-log.ts <raw-log-path>");
writeFileSync(output, "");
const counts = new Map<string, number>();
for await (const line of createInterface({ input: process.stdin, crlfDelay: Infinity })) {
	appendFileSync(output, `${line}\n`);
	const reason = exclusion.exec(line)?.[1];
	if (reason) {
		const count = counts.get(reason) ?? 0;
		// Show the first instance immediately, rather than disguising the warning's provenance.
		if (count === 0) console.log(line);
		counts.set(reason, count + 1);
	} else {
		console.log(line);
	}
}
if (counts.size > 0) {
	console.log(
		"CDS archive diagnostics (log-line counts; all class names retained in the buildpacks-log artifact):",
	);
	for (const [reason, count] of counts) console.log(`  ${reason}: ${count}`);
}
