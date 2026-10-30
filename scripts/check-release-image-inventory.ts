import { readFileSync } from "node:fs";

import { parse } from "yaml";

import { chartImages } from "./kubernetes-chart.ts";
import {
	renderDeployTopology,
	readDeployContainers,
	topologyImage,
	type DeployContainer,
} from "./lib/deploy-topology.ts";
import { asRecord, readJsonFileSync } from "./lib/json.ts";

interface UpstreamImage {
	name: string;
	repository: string;
	digest: string;
}

function isUpstreamImage(value: unknown): value is UpstreamImage {
	return (
		typeof value === "object" &&
		value !== null &&
		"name" in value &&
		"repository" in value &&
		"digest" in value &&
		typeof value.name === "string" &&
		typeof value.repository === "string" &&
		typeof value.digest === "string" &&
		/^sha256:[a-f0-9]{64}$/u.test(value.digest)
	);
}

/** The images the CI image workflow can publish: its `IMAGE_NAMES` list. */
export function releaseImages(workflow: string): string[] {
	const list = /^ {2}IMAGE_NAMES: '(?<names>\[[^']*\])'$/mu.exec(workflow)?.groups?.names;
	const images: unknown = list === undefined ? [] : JSON.parse(list);
	if (
		!Array.isArray(images) ||
		images.length === 0 ||
		images.some((image) => typeof image !== "string")
	) {
		throw new Error("the image workflow selects no images");
	}
	return images.map(String);
}

export function validateInventory(inventory: unknown, workflow: string): string[] {
	if (
		typeof inventory !== "object" ||
		inventory === null ||
		!("images" in inventory) ||
		!Array.isArray(inventory.images) ||
		inventory.images.some((image) => typeof image !== "string" || !/^[a-z0-9-]+$/u.test(image))
	) {
		throw new Error("malformed release image inventory");
	}
	const configured = inventory.images;
	if (new Set(configured).size !== configured.length) {
		throw new Error("release image inventory contains duplicates");
	}
	if (!("upstream" in inventory) || !Array.isArray(inventory.upstream)) {
		throw new Error("malformed upstream image inventory");
	}
	const upstream = new Set<string>();
	for (const image of inventory.upstream) {
		if (!isUpstreamImage(image)) {
			throw new Error("malformed upstream image inventory");
		}
		if (configured.includes(image.name) || upstream.has(image.name)) {
			throw new Error("release image inventory contains duplicates");
		}
		upstream.add(image.name);
	}

	return releaseImages(workflow).filter((image) => !configured.includes(image));
}

/** The chart's actual containers and sandbox image input must consume the verified release pins. */
export function deployedImageConsumers(containers: readonly DeployContainer[]): string[] {
	const expected = new Map(chartImages.map((name) => [topologyImage(name), name]));
	const consumers = new Set<string>();
	for (const container of containers) {
		const references = [
			container.image,
			container.environment.get("HEPHAESTUS_AGENT_IMAGE_REFERENCE"),
		].filter((reference) => reference !== undefined);
		for (const reference of references) {
			const name = expected.get(reference);
			if (name === undefined) {
				throw new Error(`${container.name} bypasses verified chart image values`);
			}
			consumers.add(name);
		}
	}
	if (consumers.size === 0) {
		throw new Error("the chart selects no image consumers");
	}
	return [...consumers].toSorted();
}

export function validateChartConsumers(
	consumers: readonly string[],
	schemaImages: readonly string[],
	inventoried: ReadonlySet<string>,
): void {
	for (const name of new Set([...consumers, ...schemaImages, ...chartImages])) {
		if (!chartImages.some((image) => image === name)) {
			throw new Error(`unexpected chart image ${name}`);
		}
		if (!consumers.includes(name)) {
			throw new Error(`${name}: missing consumer`);
		}
		if (!schemaImages.includes(name)) {
			throw new Error(`${name}: missing schema`);
		}
		if (!inventoried.has(name)) {
			throw new Error(`${name}: missing evidence`);
		}
	}
}

/** Preserve verification of the 0.95 host bundle until its consumers and evidence retire together. */
export function composeImageConsumers(
	text: string,
	file: string,
	known: ReadonlySet<string>,
): Set<string> {
	const document: unknown = parse(text, { merge: true });
	const services = asRecord(asRecord(document, file).services, `${file} services`);
	if (Object.keys(services).length === 0) {
		throw new Error(`${file} has no services`);
	}
	const consumers = new Set<string>();
	for (const [name, raw] of Object.entries(services)) {
		const service = asRecord(raw, `${file}:${name}`);
		const variable =
			typeof service.image === "string"
				? /^\$\{HEPHAESTUS_IMAGE_(?<name>[A-Z0-9_]+):\?[^}]+\}$/u.exec(service.image)?.groups?.name
				: undefined;
		const image = variable?.toLowerCase().replaceAll("_", "-");
		if (image === undefined || !known.has(image)) {
			throw new Error(`${file}:${name} bypasses the verified release lock`);
		}
		consumers.add(image);
	}
	return consumers;
}

export function validateRetained(
	known: ReadonlySet<string>,
	chart: readonly string[],
	retained: ReadonlySet<string>,
): void {
	for (const name of known) {
		if (!chart.includes(name) && !retained.has(name)) {
			throw new Error(`release image ${name} has no evidenced deployment consumer`);
		}
	}
}

if (import.meta.main) {
	const inventory: unknown = readJsonFileSync("security/release-images.json");
	const missing = validateInventory(
		inventory,
		readFileSync(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	if (missing.length > 0) {
		throw new Error(`release images missing from evidence inventory: ${missing.join(", ")}`);
	}
	const source = asRecord(inventory, "release inventory");
	if (source.schemaVersion !== 1) {
		throw new Error("release image inventory must declare schemaVersion 1");
	}
	if (!Array.isArray(source.images) || !Array.isArray(source.upstream)) {
		throw new Error("malformed release image inventory");
	}
	const known = new Set(
		source.images.filter((image): image is string => typeof image === "string"),
	);
	for (const image of source.upstream) {
		if (!isUpstreamImage(image)) {
			throw new Error("malformed upstream image inventory");
		}
		if (known.has(image.name)) {
			throw new Error(`duplicate release image ${image.name}`);
		}
		known.add(image.name);
	}
	const consumers = deployedImageConsumers(readDeployContainers(renderDeployTopology()));
	const schema = asRecord(
		readJsonFileSync("deploy/chart/hephaestus/values.schema.json"),
		"chart schema",
	);
	const images = asRecord(asRecord(schema.properties, "chart properties").images, "chart images");
	validateChartConsumers(
		consumers,
		Object.keys(asRecord(images.properties, "chart image properties")),
		known,
	);
	// The 0.95 release still evidences the proxy and volume initializer. CT-1 removes their consumers
	// and inventory entries together; the chart never treats those images as chart values.
	const retained = new Set<string>();
	for (const file of [
		"docker/compose.app.yaml",
		"docker/compose.core.yaml",
		"docker/compose.proxy.yaml",
	]) {
		for (const name of composeImageConsumers(readFileSync(file, "utf8"), file, known)) {
			if (!consumers.includes(name)) {
				retained.add(name);
			}
		}
	}

	validateRetained(known, consumers, retained);
	console.log(
		`release image inventory: ${consumers.length} chart consumers, ${retained.size} retained release consumers`,
	);
}
