import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { isSet } from "./lib/env.ts";
import { asRecord, asString, parseJson } from "./lib/json.ts";

export async function verifyReleaseCheck(
	candidate: string,
	lockFile: string,
	planFile: string,
	signature: string,
	allowedSigners: string,
): Promise<void> {
	const planBytes = await readFile(planFile);
	const plan = asRecord(parseJson(planBytes.toString("utf8")), "Release plan");
	const lock = await readFile(lockFile);
	const digest = `sha256:${createHash("sha256").update(lock).digest("hex")}`;
	if (plan.candidate !== candidate || plan.lockDigest !== digest) {
		throw new Error(
			"The release plan does not cover the selected candidate and signed lock digest",
		);
	}

	const template = asRecord(
		parseJson(await readFile(new URL("templates/release-plan.json", import.meta.url), "utf8")),
		"Release plan template",
	);
	const checks = asRecord(plan.checks, "Release plan checks");
	for (const id of Object.keys(asRecord(template.checks, "Release plan template checks"))) {
		const result = checks[id];
		if (typeof result !== "string" || result.trim() === "") {
			throw new Error(`The release plan has no result for ${id}`);
		}
	}
	execFileSync(
		"ssh-keygen",
		[
			"-Y",
			"verify",
			"-f",
			allowedSigners,
			"-I",
			asString(plan.signer, "Release plan signer"),
			"-n",
			"hephaestus-release-check",
			"-s",
			signature,
		],
		{ input: planBytes, stdio: ["pipe", "inherit", "inherit"], timeout: 10_000 },
	);
}

if (import.meta.main) {
	const [candidate, lock, plan, signature] = process.argv.slice(2);
	if (!isSet(candidate) || !isSet(lock) || !isSet(plan) || !isSet(signature)) {
		throw new Error("Expected the candidate tag, verified lock, release plan and signature");
	}
	await verifyReleaseCheck(
		candidate,
		lock,
		plan,
		signature,
		fileURLToPath(new URL("../.github/release-check-signers", import.meta.url)),
	);
}
