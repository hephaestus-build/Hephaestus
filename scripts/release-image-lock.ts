import { readFileSync, writeFileSync } from "node:fs";

const digestPattern = /^sha256:[a-f0-9]{64}$/;
const commitPattern = /^[a-f0-9]{40}$/;
const repositoryPattern = /^(?:ghcr\.io|docker\.io)\/[a-z0-9][a-z0-9._/-]*$/;
const platforms = ["linux/amd64", "linux/arm64"] as const;

type Subject = {
	image: string;
	repository: string;
	provenance: "first-party" | "upstream";
	indexDigest: string;
	platforms: Record<(typeof platforms)[number], string>;
};

export type ReleaseImageLock = {
	schemaVersion: 1;
	release: string;
	commit: string;
	images: Subject[];
};

export function isRelease(value: string): boolean {
	if (!value.startsWith("v")) return false;
	const separator = value.indexOf("-");
	const version = value.slice(1, separator < 0 ? undefined : separator);
	const prerelease = separator < 0 ? undefined : value.slice(separator + 1);
	const core = version.split(".");
	if (core.length !== 3 || core.some((part) => !/^(?:0|[1-9]\d*)$/.test(part))) return false;
	if (prerelease === undefined) return true;
	const identifiers = prerelease.split(".");
	return identifiers.every(
		(identifier) =>
			identifier.length > 0 &&
			/^[0-9A-Za-z-]+$/.test(identifier) &&
			(!/^\d+$/.test(identifier) || !identifier.startsWith("0") || identifier === "0"),
	);
}

function record(value: unknown): Record<string, unknown> {
	if (typeof value !== "object" || value === null || Array.isArray(value))
		throw new Error("expected an object");
	return Object.fromEntries(Object.entries(value));
}

function exactKeys(value: Record<string, unknown>, expected: string[], context: string): void {
	const actual = Object.keys(value).toSorted();
	if (actual.join("\0") !== expected.toSorted().join("\0"))
		throw new Error(`${context} has missing or extra fields`);
}

export function parseReleaseImageLock(value: unknown, expectedRelease?: string): ReleaseImageLock {
	const lock = record(value);
	exactKeys(lock, ["schemaVersion", "release", "commit", "images"], "lock");
	if (lock.schemaVersion !== 1) throw new Error("unsupported lock schema");
	if (typeof lock.release !== "string" || !isRelease(lock.release))
		throw new Error("malformed release");
	if (expectedRelease !== undefined && lock.release !== expectedRelease)
		throw new Error(`lock is for ${lock.release}, not ${expectedRelease}`);
	if (typeof lock.commit !== "string" || !commitPattern.test(lock.commit))
		throw new Error("malformed source commit");
	if (!Array.isArray(lock.images) || lock.images.length === 0)
		throw new Error("lock has no images");

	const names = new Set<string>();
	const references = new Set<string>();
	const images = lock.images.map((input, index): Subject => {
		const image = record(input);
		exactKeys(
			image,
			["image", "repository", "provenance", "indexDigest", "platforms"],
			`image ${index}`,
		);
		if (typeof image.image !== "string" || !/^[a-z0-9][a-z0-9-]*$/.test(image.image))
			throw new Error(`image ${index} has a malformed name`);
		if (names.has(image.image)) throw new Error(`duplicate image ${image.image}`);
		names.add(image.image);
		if (typeof image.repository !== "string" || !repositoryPattern.test(image.repository))
			throw new Error(`${image.image} has a malformed repository`);
		if (image.provenance !== "first-party" && image.provenance !== "upstream")
			throw new Error(`${image.image} has a malformed provenance class`);
		if (typeof image.indexDigest !== "string" || !digestPattern.test(image.indexDigest))
			throw new Error(`${image.image} has a malformed index digest`);
		const reference = `${image.repository}@${image.indexDigest}`;
		if (references.has(reference)) throw new Error(`duplicate locked reference ${reference}`);
		references.add(reference);
		const children = record(image.platforms);
		exactKeys(children, [...platforms], `${image.image} platforms`);
		const amd64 = children["linux/amd64"];
		const arm64 = children["linux/arm64"];
		if (typeof amd64 !== "string" || !digestPattern.test(amd64))
			throw new Error(`${image.image} has a malformed linux/amd64 digest`);
		if (typeof arm64 !== "string" || !digestPattern.test(arm64))
			throw new Error(`${image.image} has a malformed linux/arm64 digest`);
		return {
			image: image.image,
			repository: image.repository,
			provenance: image.provenance,
			indexDigest: image.indexDigest,
			platforms: { "linux/amd64": amd64, "linux/arm64": arm64 },
		};
	});
	return { schemaVersion: 1, release: lock.release, commit: lock.commit, images };
}

export function verifyLockAgainstEvidence(lock: ReleaseImageLock, evidenceValue: unknown): void {
	const evidence = record(evidenceValue);
	if (
		evidence.schemaVersion !== 1 ||
		evidence.release !== lock.release ||
		evidence.commit !== lock.commit
	)
		throw new Error("lock release identity does not match the evidence manifest");
	if (!Array.isArray(evidence.subjects)) throw new Error("evidence manifest has no subjects");
	const expected = new Map<string, Subject>();
	for (const image of lock.images) expected.set(image.image, image);
	const seen = new Set<string>();
	for (const input of evidence.subjects) {
		const subject = record(input);
		const image = typeof subject.image === "string" ? expected.get(subject.image) : undefined;
		if (!image) throw new Error(`evidence contains an unlocked image ${String(subject.image)}`);
		if (subject.platform !== "linux/amd64" && subject.platform !== "linux/arm64")
			throw new Error(`evidence has an unsupported platform for ${image.image}`);
		const key = `${image.image}/${subject.platform}`;
		if (seen.has(key)) throw new Error(`duplicate evidence subject ${key}`);
		seen.add(key);
		if (
			subject.repository !== image.repository ||
			subject.provenance !== image.provenance ||
			subject.indexDigest !== image.indexDigest ||
			subject.digest !== image.platforms[subject.platform]
		)
			throw new Error(`lock and evidence disagree for ${key}`);
	}
	for (const image of lock.images)
		for (const platform of platforms)
			if (!seen.has(`${image.image}/${platform}`))
				throw new Error(`evidence is missing ${image.image}/${platform}`);
}

export function lockEnvironment(lock: ReleaseImageLock): string {
	const lines = [
		`IMAGE_TAG=${lock.release.slice(1)}`,
		`HEPHAESTUS_RELEASE=${lock.release}`,
		`HEPHAESTUS_RELEASE_COMMIT=${lock.commit}`,
	];
	for (const image of lock.images.toSorted((left, right) =>
		left.image.localeCompare(right.image),
	)) {
		const key = image.image.toUpperCase().replaceAll("-", "_");
		lines.push(`HEPHAESTUS_IMAGE_${key}=${image.repository}@${image.indexDigest}`);
	}
	return `${lines.join("\n")}\n`;
}

if (import.meta.main) {
	const [lockPath, evidencePath, expectedRelease, outputPath] = process.argv.slice(2);
	if (!lockPath || !evidencePath || !expectedRelease || !outputPath)
		throw new Error("usage: release-image-lock <lock.json> <manifest.json> <release> <env-output>");
	const lock = parseReleaseImageLock(
		JSON.parse(readFileSync(lockPath, "utf8")) as unknown,
		expectedRelease,
	);
	verifyLockAgainstEvidence(lock, JSON.parse(readFileSync(evidencePath, "utf8")) as unknown);
	writeFileSync(outputPath, lockEnvironment(lock), { flag: "wx", mode: 0o600 });
}
