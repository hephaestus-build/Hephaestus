import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { asRecord, readJsonFile } from "./lib/json.ts";
import { verifyReleaseCheck } from "./verify-release-check.ts";

void test(
	"release sign-off covers the full plan and exact candidate lock bytes",
	{ skip: process.platform === "win32" && "Release jobs run on Linux with OpenSSH" },
	async (t) => {
		const directory = await mkdtemp(path.join(tmpdir(), "release-check-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const key = path.join(directory, "key");
		const otherKey = path.join(directory, "other-key");
		const planFile = path.join(directory, "release-plan.json");
		const signature = `${planFile}.sig`;
		const lock = path.join(directory, "candidate.chart.json");
		const allowedSigners = path.join(directory, "allowed-signers");
		const candidate = "v1.0.0-rc.2";
		const namespace = "hephaestus-release-check";

		for (const file of [key, otherKey]) {
			execFileSync("ssh-keygen", ["-q", "-t", "ed25519", "-N", "", "-f", file]);
		}
		const publicKey = await readFile(`${key}.pub`, "utf8");
		await writeFile(allowedSigners, `maintainer namespaces="${namespace}" ${publicKey}`);
		const lockBytes = '{"chart":{"digest":"sha256:abc"}}\n';
		await writeFile(lock, lockBytes);
		const template = asRecord(
			await readJsonFile("scripts/templates/release-plan.json"),
			"template",
		);
		const checks = Object.fromEntries(
			Object.keys(asRecord(template.checks, "template checks")).map((id) => [id, "Passed"]),
		);
		const plan = {
			candidate,
			lockDigest: `sha256:${createHash("sha256").update(lockBytes).digest("hex")}`,
			signer: "maintainer",
			checks,
		};
		const sign = async (signingKey: string, purpose = namespace) => {
			await rm(signature, { force: true });
			execFileSync("ssh-keygen", ["-Y", "sign", "-f", signingKey, "-n", purpose, planFile], {
				stdio: "ignore",
			});
		};
		const verify = async (tag = candidate) =>
			verifyReleaseCheck(tag, lock, planFile, signature, allowedSigners);
		await writeFile(planFile, JSON.stringify(plan));
		await assert.rejects(verify(), /ssh-keygen/u);
		await sign(key);
		await verify();

		await assert.rejects(verify("v1.0.0-rc.3"), /selected candidate/u);
		await writeFile(lock, lockBytes.replace("abc", "def"));
		await assert.rejects(verify(), /signed lock digest/u);
		await writeFile(lock, lockBytes);

		await writeFile(planFile, JSON.stringify({ ...plan, checks: { ...checks, speech: "Edited" } }));
		await assert.rejects(verify(), /ssh-keygen/u);
		for (const id of ["packaged-isolation", "rubric-judge"]) {
			await writeFile(planFile, JSON.stringify({ ...plan, checks: { ...checks, [id]: " " } }));
			await sign(key);
			await assert.rejects(verify(), { message: `The release plan has no result for ${id}` });
		}

		await writeFile(planFile, JSON.stringify(plan));
		await sign(otherKey);
		await assert.rejects(verify(), /ssh-keygen/u);
		await sign(key, "git");
		await assert.rejects(verify(), /ssh-keygen/u);
	},
);
