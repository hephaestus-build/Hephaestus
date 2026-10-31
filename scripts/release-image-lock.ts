import { readFileSync, writeFileSync } from "node:fs";

const digestPattern = /^sha256:[a-f0-9]{64}$/u;
const commitPattern = /^[a-f0-9]{40}$/u;
const repositoryPattern = /^(?:ghcr\.io|docker\.io)\/[a-z0-9][a-z0-9._/-]*$/u;
/** The chart is first-party: it lives in the release's own GHCR namespace, under one fixed name. */
const chartRepositoryPattern =
	/^ghcr\.io\/[a-z0-9][a-z0-9._-]*(?:\/[a-z0-9][a-z0-9._-]*)*\/charts\/hephaestus$/u;
const platforms = ["linux/amd64", "linux/arm64"] as const;

interface Subject {
	image: string;
	repository: string;
	provenance: "first-party" | "upstream";
	indexDigest: string;
	platforms: Record<(typeof platforms)[number], string>;
}

/** The OCI chart of a release, by the manifest digest that its push returned. */
export interface ReleaseChart {
	repository: string;
	version: string;
	digest: string;
}

interface LockIdentity {
	release: string;
	commit: string;
	images: Subject[];
}

/**
 * Image identities before chart packaging. This shape is not a published release lock.
 */
export interface ReleaseImageLockV1 extends LockIdentity {
	schemaVersion: 1;
}

/** Schema 2: the same images and source, plus the chart packaged from that source. */
export interface ReleaseImageLockV2 extends LockIdentity {
	schemaVersion: 2;
	chart: ReleaseChart;
}

export type ReleaseImageLock = ReleaseImageLockV1 | ReleaseImageLockV2;

/** The chart version of a release: the release without its leading "v", as `helm package` takes it. */
export function chartVersion(release: string): string {
	if (!isRelease(release)) {
		throw new Error("malformed release");
	}
	return release.slice(1);
}

function parseChart(value: unknown, release: string): ReleaseChart {
	const chart = record(value);
	exactKeys(chart, ["repository", "version", "digest"], "chart");
	if (typeof chart.repository !== "string" || !chartRepositoryPattern.test(chart.repository)) {
		throw new Error("chart has a malformed repository");
	}
	if (chart.version !== chartVersion(release)) {
		throw new Error(`chart version is not ${chartVersion(release)}`);
	}
	if (typeof chart.digest !== "string" || !digestPattern.test(chart.digest)) {
		throw new Error("chart has a malformed digest");
	}
	return { repository: chart.repository, version: chart.version, digest: chart.digest };
}

export function isRelease(value: string): boolean {
	if (!value.startsWith("v")) {
		return false;
	}
	const separator = value.indexOf("-");
	const version = value.slice(1, separator === -1 ? undefined : separator);
	const prerelease = separator === -1 ? undefined : value.slice(separator + 1);
	const core = version.split(".");
	if (core.length !== 3 || core.some((part) => !/^(?:0|[1-9]\d*)$/u.test(part))) {
		return false;
	}
	if (prerelease === undefined) {
		return true;
	}
	const identifiers = prerelease.split(".");
	return identifiers.every(
		(identifier) =>
			identifier.length > 0 &&
			/^[0-9A-Za-z-]+$/u.test(identifier) &&
			(!/^\d+$/u.test(identifier) || !identifier.startsWith("0") || identifier === "0"),
	);
}

function record(value: unknown): Record<string, unknown> {
	if (typeof value !== "object" || value === null || Array.isArray(value)) {
		throw new Error("expected an object");
	}
	return Object.fromEntries(Object.entries(value));
}

function exactKeys(value: Record<string, unknown>, expected: string[], context: string): void {
	const actual = Object.keys(value).toSorted();
	if (actual.join("\0") !== expected.toSorted().join("\0")) {
		throw new Error(`${context} has missing or extra fields`);
	}
}

export function parseReleaseImageLock(value: unknown, expectedRelease?: string): ReleaseImageLock {
	const lock = record(value);
	if (lock.schemaVersion !== 1 && lock.schemaVersion !== 2) {
		throw new Error("unsupported lock schema");
	}
	exactKeys(
		lock,
		lock.schemaVersion === 1
			? ["schemaVersion", "release", "commit", "images"]
			: ["schemaVersion", "release", "commit", "images", "chart"],
		"lock",
	);
	if (typeof lock.release !== "string" || !isRelease(lock.release)) {
		throw new Error("malformed release");
	}
	if (expectedRelease !== undefined && lock.release !== expectedRelease) {
		throw new Error(`lock is for ${lock.release}, not ${expectedRelease}`);
	}
	if (typeof lock.commit !== "string" || !commitPattern.test(lock.commit)) {
		throw new Error("malformed source commit");
	}
	if (!Array.isArray(lock.images) || lock.images.length === 0) {
		throw new Error("lock has no images");
	}

	const names = new Set<string>();
	const references = new Set<string>();
	const images = lock.images.map((input, index): Subject => {
		const image = record(input);
		exactKeys(
			image,
			["image", "repository", "provenance", "indexDigest", "platforms"],
			`image ${index}`,
		);
		if (typeof image.image !== "string" || !/^[a-z0-9][a-z0-9-]*$/u.test(image.image)) {
			throw new Error(`image ${index} has a malformed name`);
		}
		if (names.has(image.image)) {
			throw new Error(`duplicate image ${image.image}`);
		}
		names.add(image.image);
		if (typeof image.repository !== "string" || !repositoryPattern.test(image.repository)) {
			throw new Error(`${image.image} has a malformed repository`);
		}
		if (image.provenance !== "first-party" && image.provenance !== "upstream") {
			throw new Error(`${image.image} has a malformed provenance class`);
		}
		if (typeof image.indexDigest !== "string" || !digestPattern.test(image.indexDigest)) {
			throw new Error(`${image.image} has a malformed index digest`);
		}
		const reference = `${image.repository}@${image.indexDigest}`;
		if (references.has(reference)) {
			throw new Error(`duplicate locked reference ${reference}`);
		}
		references.add(reference);
		const children = record(image.platforms);
		exactKeys(children, [...platforms], `${image.image} platforms`);
		const amd64 = children["linux/amd64"];
		const arm64 = children["linux/arm64"];
		if (typeof amd64 !== "string" || !digestPattern.test(amd64)) {
			throw new Error(`${image.image} has a malformed linux/amd64 digest`);
		}
		if (typeof arm64 !== "string" || !digestPattern.test(arm64)) {
			throw new Error(`${image.image} has a malformed linux/arm64 digest`);
		}
		return {
			image: image.image,
			repository: image.repository,
			provenance: image.provenance,
			indexDigest: image.indexDigest,
			platforms: { "linux/amd64": amd64, "linux/arm64": arm64 },
		};
	});
	const identity = { release: lock.release, commit: lock.commit, images };
	if (lock.schemaVersion === 1) {
		return { schemaVersion: 1, ...identity };
	}
	return { schemaVersion: 2, ...identity, chart: parseChart(lock.chart, lock.release) };
}

export function verifyLockAgainstEvidence(lock: ReleaseImageLock, evidenceValue: unknown): void {
	const evidence = record(evidenceValue);
	if (
		evidence.schemaVersion !== 1 ||
		evidence.release !== lock.release ||
		evidence.commit !== lock.commit
	) {
		throw new Error("lock release identity does not match the evidence manifest");
	}
	if (!Array.isArray(evidence.subjects)) {
		throw new TypeError("evidence manifest has no subjects");
	}
	const expected = new Map<string, Subject>();
	for (const image of lock.images) {
		expected.set(image.image, image);
	}
	const seen = new Set<string>();
	for (const input of evidence.subjects) {
		const subject = record(input);
		const image = typeof subject.image === "string" ? expected.get(subject.image) : undefined;
		if (!image) {
			throw new Error(`evidence contains an unlocked image ${String(subject.image)}`);
		}
		if (subject.platform !== "linux/amd64" && subject.platform !== "linux/arm64") {
			throw new Error(`evidence has an unsupported platform for ${image.image}`);
		}
		const key = `${image.image}/${subject.platform}`;
		if (seen.has(key)) {
			throw new Error(`duplicate evidence subject ${key}`);
		}
		seen.add(key);
		if (
			subject.repository !== image.repository ||
			subject.provenance !== image.provenance ||
			subject.indexDigest !== image.indexDigest ||
			subject.digest !== image.platforms[subject.platform]
		) {
			throw new Error(`lock and evidence disagree for ${key}`);
		}
	}
	for (const image of lock.images) {
		for (const platform of platforms) {
			if (!seen.has(`${image.image}/${platform}`)) {
				throw new Error(`evidence is missing ${image.image}/${platform}`);
			}
		}
	}
}

/** The signed release lock covers the chart, its images and source commit. */
export function jointLockAsset(release: string): string {
	return `release-${release}.chart.json`;
}

/**
 * The images and source of a release, built once from the evidence manifest that the release
 * verified. The chart's image values and the joint lock come from this
 * object, so no two of them can name different images.
 */
export function lockFromEvidence(evidenceValue: unknown, release: string): ReleaseImageLockV1 {
	const evidence = record(evidenceValue);
	if (evidence.release !== release || !Array.isArray(evidence.subjects)) {
		throw new Error(`the evidence manifest does not describe ${release}`);
	}
	const groups = new Map<string, Record<string, unknown>[]>();
	for (const input of evidence.subjects) {
		const subject = record(input);
		if (typeof subject.image !== "string") {
			throw new TypeError("evidence subject has no image name");
		}
		groups.set(subject.image, [...(groups.get(subject.image) ?? []), subject]);
	}
	const images = [...groups.entries()]
		.toSorted(([left], [right]) => (left < right ? -1 : 1))
		.map(([image, subjects]) => {
			const [first] = subjects;
			for (const subject of subjects) {
				if (
					subject.repository !== first?.repository ||
					subject.provenance !== first?.provenance ||
					subject.indexDigest !== first?.indexDigest
				) {
					throw new Error(`the evidence manifest disagrees about ${image}`);
				}
			}
			return {
				image,
				repository: first?.repository,
				provenance: first?.provenance,
				indexDigest: first?.indexDigest,
				// Only the two supported platform keys are read from the evidence; their digests stay
				// unknown until the strict parse below. A missing platform leaves its key out, so the
				// parse refuses it, and verifyLockAgainstEvidence refuses any other platform or a duplicate.
				platforms: Object.fromEntries(
					platforms.flatMap((platform): [string, unknown][] => {
						const subject = subjects.find((candidate) => candidate.platform === platform);
						return subject === undefined ? [] : [[platform, subject.digest]];
					}),
				),
			};
		});
	const lock = parseReleaseImageLock(
		{ schemaVersion: 1, release, commit: evidence.commit, images },
		release,
	);
	if (lock.schemaVersion !== 1) {
		throw new Error("expected a schema 1 lock");
	}
	verifyLockAgainstEvidence(lock, evidenceValue);
	return lock;
}

/** The joint lock of a release: the lock from its evidence, plus the chart that it pushed. */
export function jointLockFromEvidence(
	evidenceValue: unknown,
	chartValue: unknown,
	release: string,
): ReleaseImageLockV2 {
	const lock = parseReleaseImageLock(
		{ ...lockFromEvidence(evidenceValue, release), schemaVersion: 2, chart: chartValue },
		release,
	);
	if (lock.schemaVersion !== 2) {
		throw new Error("expected a joint lock");
	}
	return lock;
}

const lockJson = (lock: ReleaseImageLock) => `${JSON.stringify(lock, null, 2)}\n`;
const parseJson = (file: string): unknown => JSON.parse(readFileSync(file, "utf8"));

if (import.meta.main && process.argv[2] === "write") {
	const [manifestPath = "", chartPath = "", release = "", jointPath = ""] = process.argv.slice(3);
	if (
		process.argv.length !== 7 ||
		manifestPath === "" ||
		chartPath === "" ||
		release === "" ||
		jointPath === ""
	) {
		throw new Error(
			"usage: release-image-lock write <manifest.json> <chart.json> <release> <joint-output>",
		);
	}
	const joint = jointLockFromEvidence(parseJson(manifestPath), parseJson(chartPath), release);
	writeFileSync(jointPath, lockJson(joint), { flag: "wx" });
} else if (import.meta.main && process.argv[2] === "verify") {
	const [lockPath = "", evidencePath = "", expectedRelease = ""] = process.argv.slice(3);
	if (
		process.argv.length !== 6 ||
		lockPath === "" ||
		evidencePath === "" ||
		expectedRelease === ""
	) {
		throw new Error("usage: release-image-lock verify <lock.json> <manifest.json> <release>");
	}
	const lock = parseReleaseImageLock(parseJson(lockPath), expectedRelease);
	if (lock.schemaVersion !== 2) {
		throw new Error("expected a joint lock");
	}
	verifyLockAgainstEvidence(lock, parseJson(evidencePath));
} else if (import.meta.main) {
	throw new Error("usage: release-image-lock <write|verify> ...");
}
