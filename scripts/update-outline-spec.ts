/**
 * Refreshes the vendored Outline OpenAPI spec, which the OpenAPI Generator Gradle plugin turns into
 * the client models. Vendoring makes each refresh a reviewable diff instead of a build that changes
 * under you, and the content is checked before it reaches disk for the reason `update-github-schema`
 * gives: a public URL can answer 200 with something that is not the document.
 *
 * `outline-supplement.yaml` beside it is hand-authored and untouched here. It carries the endpoints
 * Outline has not documented upstream, and each entry is deleted as it lands in `spec3.yml`.
 */

import { renameSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const SPEC_DIR = resolve(
	import.meta.dirname,
	"../server/generated-clients/src/main/resources/openapi/outline",
);
const SPEC_FILE = join(SPEC_DIR, "spec3.yml");
const SPEC_URL = "https://raw.githubusercontent.com/outline/openapi/main/spec3.yml";

// The spec runs a few hundred kilobytes; an error page or a redirect notice is nowhere near.
const MIN_SIZE_BYTES = 50_000;
const MAX_SIZE_BYTES = 10_000_000;

/** Cheapest checks first: a wrong body is usually the wrong size, and never reaches the regexes. */
function validateSpec(content: string): { valid: boolean; reason?: string } {
	if (content.length < MIN_SIZE_BYTES) {
		return {
			valid: false,
			reason: `Content too small (${content.length} bytes, minimum ${MIN_SIZE_BYTES})`,
		};
	}
	if (content.length > MAX_SIZE_BYTES) {
		return {
			valid: false,
			reason: `Content too large (${content.length} bytes, maximum ${MAX_SIZE_BYTES})`,
		};
	}
	if (content.includes("\0")) {
		return {
			valid: false,
			reason: "Content contains null bytes (possible binary data)",
		};
	}
	if (!/^openapi:\s*3\./m.test(content)) {
		return { valid: false, reason: "Content is not an OpenAPI 3 document" };
	}
	if (!/title:\s*Outline API/.test(content)) {
		return {
			valid: false,
			reason: "Content is missing the expected 'Outline API' title",
		};
	}
	if (!/^\s*schemas:/m.test(content) || !/^\s*Document:/m.test(content)) {
		return {
			valid: false,
			reason: "Content is missing expected Outline component schemas",
		};
	}
	return { valid: true };
}

async function main(): Promise<void> {
	console.log("Downloading Outline OpenAPI spec...");
	console.log(`Source: ${SPEC_URL}`);

	const response = await fetch(SPEC_URL);
	if (!response.ok) {
		console.error(`Failed to fetch spec: ${response.status} ${response.statusText}`);
		process.exit(1);
	}

	const contentType = response.headers.get("content-type") ?? "";
	const acceptable = ["text/", "application/octet-stream", "application/yaml"];
	if (!acceptable.some((type) => contentType.includes(type))) {
		console.error(`Unexpected Content-Type: ${contentType}`);
		console.error(`Expected one of: ${acceptable.join(", ")}`);
		process.exit(1);
	}

	const content = await response.text();

	console.log("Validating spec content...");
	const validation = validateSpec(content);
	if (!validation.valid) {
		console.error(`Spec validation failed: ${validation.reason}`);
		process.exit(1);
	}

	const tempFile = `${SPEC_FILE}.tmp`;
	try {
		writeFileSync(tempFile, content, "utf8");
		const stats = statSync(tempFile);
		console.log(`Downloaded ${Math.round(stats.size / 1024)}KB`);
		renameSync(tempFile, SPEC_FILE);
		console.log(`Spec updated successfully: ${SPEC_FILE}`);
		console.log("\nTo regenerate models: cd server && ./gradlew :generated-clients:classes");
	} catch (error) {
		try {
			unlinkSync(tempFile);
		} catch {
			// The write is the failure worth reporting; a failed cleanup must not replace it.
		}
		throw error;
	}
}

main().catch((error) => {
	console.error("Error updating spec:", error);
	process.exit(1);
});
