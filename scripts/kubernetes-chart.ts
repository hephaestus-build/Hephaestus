/**
 * Turns a release lock into the chart values that name the release's images.
 *
 * The lock is the one authority for what a release runs (ADR 0034). This step adds no image, tag or
 * digest of its own: it checks that the lock covers exactly the release image inventory and its
 * upstream pins, then copies the index digests of the images the chart runs. It is not a signature
 * check: the release calls it with the lock that it built from its verified evidence, and writes
 * the result into the chart package that it then signs. The reconciler writes a commit channel's
 * values with `commitChartValues`, after it verified the channel.
 */
import { environmentKey, type ImageInventory } from "./commit-image-lock.ts";
import type { ReleaseImageLock } from "./release-image-lock.ts";

/**
 * The lock images that the chart runs, in the order the values file lists them. The edge proxy and
 * the volume initializer have no Kubernetes counterpart: a Gateway replaces the first, fsGroup the
 * second. deploy/chart/hephaestus/values.schema.json requires exactly these keys.
 */
export const chartImages = [
	"agent-pi",
	"application-server",
	"nats",
	"postgres",
	"webapp",
] as const;

export type ChartImage = (typeof chartImages)[number];

export interface ChartValues {
	release: { version: string; commit: string };
	images: Record<ChartImage, string>;
}

export function chartValues(lock: ReleaseImageLock, inventory: ImageInventory): ChartValues {
	const inventoried = [...inventory.images, ...inventory.upstream.map((entry) => entry.name)];
	const locked = lock.images.map((image) => image.image);
	const missing = inventoried.filter((name) => !locked.includes(name));
	const extra = locked.filter((name) => !inventoried.includes(name));
	if (missing.length > 0 || extra.length > 0) {
		throw new Error(
			`lock and release image inventory differ (missing: ${missing.join(", ") || "none"}; extra: ${extra.join(", ") || "none"})`,
		);
	}
	for (const image of lock.images) {
		const upstream = inventory.upstream.find((entry) => entry.name === image.image);
		if (image.provenance !== (upstream === undefined ? "first-party" : "upstream")) {
			throw new Error(`${image.image} has the wrong provenance class for the inventory`);
		}
		if (upstream !== undefined && upstream.repository !== image.repository) {
			throw new Error(
				`${image.image} is locked from ${image.repository}, not ${upstream.repository}`,
			);
		}
		// The release evidence takes each upstream index digest from this inventory, so a lock that
		// pins another one belongs to a different checkout than the chart.
		if (upstream !== undefined && upstream.digest !== image.indexDigest) {
			throw new Error(
				`${image.image} is locked at ${image.indexDigest}, not the inventory pin ${upstream.digest}`,
			);
		}
	}
	const reference = (name: ChartImage): string => {
		const image = lock.images.find((candidate) => candidate.image === name);
		if (image === undefined) {
			throw new Error(`the release image inventory has no ${name}, which the chart runs`);
		}
		return `${image.repository}@${image.indexDigest}`;
	};
	return {
		release: { version: lock.release, commit: lock.commit },
		images: {
			"agent-pi": reference("agent-pi"),
			"application-server": reference("application-server"),
			nats: reference("nats"),
			postgres: reference("postgres"),
			webapp: reference("webapp"),
		},
	};
}

/**
 * The release lines of the chart source's values.yaml. A release replaces exactly these lines in
 * its packaged copy; release-chart.ts then reads the package back through Helm and refuses any
 * second `release` or `images` key.
 */
const sourceValues = 'release:\n  version: ""\n  commit: ""\nimages: {}\n';

/** The values.yaml lines that name a release. Each value is a JSON string, which YAML reads as is. */
export function releaseValuesLines(values: ChartValues): string {
	const quoted = JSON.stringify;
	return [
		"release:",
		`  version: ${quoted(values.release.version)}`,
		`  commit: ${quoted(values.release.commit)}`,
		"images:",
		...chartImages.map((name) => `  ${name}: ${quoted(values.images[name])}`),
		"",
	].join("\n");
}

/** The values.yaml of a released chart: the source file with the release's lines in place. */
export function releaseValuesFile(source: string, values: ChartValues): string {
	const at = source.indexOf(sourceValues);
	if (at === -1 || (at > 0 && source[at - 1] !== "\n")) {
		throw new Error("the chart source does not hold its empty release values");
	}
	return `${source.slice(0, at)}${releaseValuesLines(values)}${source.slice(at + sourceValues.length)}`;
}

/**
 * The values of a commit channel, from the digests its verified commit lock names under the keys
 * that commit-image-lock writes. An upstream image must be the inventory's pin, as in a release
 * lock. A commit's version is the commit, as on a Compose host and as a promotion verifies it
 * (scripts/resolve-promotion.ts).
 */
export function commitChartValues(
	images: Readonly<Record<string, string>>,
	inventory: ImageInventory,
	commit: string,
): ChartValues {
	const reference = (name: ChartImage): string => {
		const locked = images[environmentKey(name)];
		if (locked === undefined || !/^[^\s@]+@sha256:[0-9a-f]{64}$/u.test(locked)) {
			throw new Error(`the commit lock names no ${name} digest, which the chart runs`);
		}
		const upstream = inventory.upstream.find((entry) => entry.name === name);
		if (upstream !== undefined && locked !== `${upstream.repository}@${upstream.digest}`) {
			throw new Error(`${name} is locked at ${locked}, not the inventory pin ${upstream.digest}`);
		}
		if (upstream === undefined && !inventory.images.includes(name)) {
			throw new Error(`the release image inventory has no ${name}, which the chart runs`);
		}
		return locked;
	};
	return {
		release: { version: commit, commit },
		images: {
			"agent-pi": reference("agent-pi"),
			"application-server": reference("application-server"),
			nats: reference("nats"),
			postgres: reference("postgres"),
			webapp: reference("webapp"),
		},
	};
}

export function renderChartValues(values: ChartValues): string {
	// JSON is YAML, and Helm reads it as a values file. Node writes it without a YAML dependency, so
	// the hosts and release jobs that run this need nothing beyond the Node runtime.
	return `# Generated by scripts/kubernetes-chart.ts from the verified ${values.release.version} release lock. Do not edit.\n${JSON.stringify(values, null, 2)}\n`;
}
