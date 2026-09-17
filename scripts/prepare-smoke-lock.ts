/**
 * Writes `docker/self-host/smoke-lock.env`, the image lock CI renders and boots the supported
 * installation with before any release exists. Every image the inventory names is set, because the
 * Compose files refuse to render without one, and every image that never runs is a placeholder no
 * registry serves.
 *
 * A job that boots the installation names the build it boots through `HEAD_SHA` and
 * `APPLICATION_DIGEST`: the application server and PostgreSQL then come from that run's own images
 * and the broker and volume initialiser from the upstream pins. A job that only renders sets neither.
 */
import { writeFile } from "node:fs/promises";
import path from "node:path";

import { DIGEST, environmentKey, readInventory, type ImageInventory } from "./commit-image-lock.ts";
import { isSet } from "./lib/env.ts";
import { SELF_HOST } from "./prepare-host-smoke-env.ts";
import { commitLockEnvironment, isCommit } from "./reconcile-deployment.ts";

export interface Build {
	commit: string;
	applicationDigest: string;
}

/** The upstream images a boot starts: the broker, and the initialiser the application server waits for. */
const BOOTED_UPSTREAM = new Set(["alpine", "nats"]);

const placeholder = (image: string): string => `example.invalid/${image}@sha256:${"0".repeat(64)}`;

export function smokeLockImages(
	inventory: ImageInventory,
	build?: Build,
): Readonly<Record<string, string>> {
	const images: Record<string, string> = {};
	for (const image of inventory.images) {
		images[environmentKey(image)] = placeholder(image);
	}
	for (const upstream of inventory.upstream) {
		images[environmentKey(upstream.name)] =
			build && BOOTED_UPSTREAM.has(upstream.name)
				? `${upstream.repository}@${upstream.digest}`
				: placeholder(upstream.name);
	}
	if (build) {
		images[environmentKey("application-server")] =
			`ghcr.io/hephaestus-build/application-server@${build.applicationDigest}`;
		images[environmentKey("postgres")] = `ghcr.io/hephaestus-build/postgres:${build.commit}`;
	}
	return images;
}

export function bootedBuild(environment: NodeJS.ProcessEnv): Build | undefined {
	const { HEAD_SHA: commit, APPLICATION_DIGEST: applicationDigest } = environment;
	const commitMissing = !isSet(commit);
	const digestMissing = !isSet(applicationDigest);
	if (commitMissing && digestMissing) {
		return undefined;
	}
	if (commitMissing || digestMissing) {
		throw new Error("HEAD_SHA and APPLICATION_DIGEST name the booted build together");
	}
	if (!isCommit(commit)) {
		throw new Error(`HEAD_SHA must be a full commit SHA, not '${commit}'`);
	}
	if (!DIGEST.test(applicationDigest)) {
		throw new Error("Build returned an invalid application image digest");
	}
	return { commit, applicationDigest };
}

if (import.meta.main) {
	const build = bootedBuild(process.env);
	const inventory = await readInventory(
		path.join(import.meta.dirname, "..", "security", "release-images.json"),
	);
	await writeFile(
		path.join(SELF_HOST, "smoke-lock.env"),
		commitLockEnvironment(build?.commit ?? "0".repeat(40), smokeLockImages(inventory, build)),
	);
}
