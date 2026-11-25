import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isSet, requiredEnv } from "./lib/env.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

const namespace = "hephaestus-release-check";
type GitHubApi = (args: string[]) => Promise<unknown>;

async function readGitHub(args: string[]): Promise<unknown> {
	return parseJson(await output("gh", ["api", ...args], { signal: AbortSignal.timeout(15_000) }));
}

function verifySignature(
	planBytes: Buffer,
	signature: string,
	signer: string,
	allowedSigners: string,
): void {
	execFileSync(
		"ssh-keygen",
		["-Y", "verify", "-f", allowedSigners, "-I", signer, "-n", namespace, "-s", signature],
		{ input: planBytes, stdio: ["pipe", "inherit", "inherit"], timeout: 10_000 },
	);
}

export async function verifyReleaseCheck(
	candidate: string,
	lockFile: string,
	planFile: string,
	signature: string,
	{
		allowedSigners,
		repository,
		githubApi = readGitHub,
	}: {
		allowedSigners: string;
		repository: string;
		githubApi?: GitHubApi;
	},
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
	const signer = asString(plan.signer, "Release plan signer");
	if (!/^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/u.test(signer)) {
		throw new Error("The release plan signer must be a GitHub login");
	}
	verifySignature(planBytes, signature, signer, allowedSigners);

	const permission = asRecord(
		await githubApi([`repos/${repository}/collaborators/${signer}/permission`]),
		"GitHub collaborator permission",
	);
	if (permission.permission !== "admin") {
		throw new Error("The release plan signer must be a repository admin");
	}
	const pages = asArray(
		await githubApi(["--paginate", "--slurp", `users/${signer}/ssh_signing_keys`]),
		"GitHub SSH signing key pages",
	);
	const keys = pages.flatMap((page) =>
		asArray(page, "GitHub SSH signing keys").map((entry) => {
			const key = asString(asRecord(entry, "GitHub SSH signing key").key, "SSH public key");
			const [type = "", blob = ""] = key.trim().split(/\s+/u);
			if (!/^[A-Za-z0-9@._+-]+$/u.test(type) || !/^[A-Za-z0-9+/]+={0,2}$/u.test(blob)) {
				throw new Error("GitHub returned an invalid SSH signing key");
			}
			return `${signer} namespaces="${namespace}" ${type} ${blob}\n`;
		}),
	);
	const directory = await mkdtemp(path.join(tmpdir(), "release-signers-"));
	try {
		const githubSigners = path.join(directory, "allowed-signers");
		await writeFile(githubSigners, keys.join(""), { mode: 0o600 });
		// Both verifications use the same signature. The candidate and GitHub must authorize its key.
		try {
			verifySignature(planBytes, signature, signer, githubSigners);
		} catch (error) {
			throw new Error("The release plan signature does not match a GitHub SSH signing key", {
				cause: error,
			});
		}
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	const [candidate, lock, plan, signature] = process.argv.slice(2);
	if (!isSet(candidate) || !isSet(lock) || !isSet(plan) || !isSet(signature)) {
		throw new Error("Expected the candidate tag, verified lock, release plan and signature");
	}
	await verifyReleaseCheck(candidate, lock, plan, signature, {
		allowedSigners: fileURLToPath(new URL("../.github/release-check-signers", import.meta.url)),
		repository: requiredEnv(process.env, "GITHUB_REPOSITORY"),
	});
}
