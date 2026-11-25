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
		const otherPublicKey = await readFile(`${otherKey}.pub`, "utf8");
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
		const repository = "owner/project";
		const permissionEndpoint = `repos/${repository}/collaborators/maintainer/permission`;
		const keysEndpoint = "users/maintainer/ssh_signing_keys";
		const permissionArgs = [permissionEndpoint];
		const keyArgs = ["--paginate", "--slurp", keysEndpoint];
		const github =
			({ permission = "admin", registeredKey = publicKey } = {}) =>
			async (args: string[]): Promise<unknown> => {
				if (args[0] === permissionEndpoint) {
					assert.deepEqual(args, permissionArgs);
					return { permission };
				}
				assert.deepEqual(args, keyArgs);
				return [[{ key: registeredKey }]];
			};
		const verify = async (tag = candidate, githubApi = github()) =>
			verifyReleaseCheck(tag, lock, planFile, signature, { allowedSigners, repository, githubApi });
		await writeFile(planFile, JSON.stringify(plan));
		await assert.rejects(verify(), /ssh-keygen/u);
		await sign(key);
		await verify();

		await t.test("rejects a pinned key absent from the signer's GitHub signing keys", async () => {
			await assert.rejects(
				verify(candidate, github({ registeredKey: otherPublicKey })),
				/GitHub SSH signing key/u,
			);
		});
		await t.test(
			"rejects a signer with repository write access but no admin permission",
			async () => {
				await assert.rejects(
					verify(candidate, github({ permission: "write" })),
					/repository admin/u,
				);
			},
		);
		await t.test("fails closed when either GitHub API call fails", async () => {
			for (const endpoint of [permissionEndpoint, keysEndpoint]) {
				const failure = new Error("GitHub API unavailable");
				const unavailable = async (args: string[]) => {
					if (args.includes(endpoint)) {
						throw failure;
					}
					return github()(args);
				};
				await assert.rejects(verify(candidate, unavailable), failure);
			}
		});

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
