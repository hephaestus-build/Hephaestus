import { readFileSync } from "node:fs";

type UpstreamImage = { name: string; repository: string; digest: string };

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
		/^sha256:[a-f0-9]{64}$/.test(value.digest)
	);
}

export function releaseImages(workflow: string): string[] {
	return [...workflow.matchAll(/image-name:\s*["']hephaestus-build\/([^"']+)["']/g)].map(
		(match) => match[1] ?? "",
	);
}

export function validateInventory(inventory: unknown, workflow: string): string[] {
	if (
		typeof inventory !== "object" ||
		inventory === null ||
		!("images" in inventory) ||
		!Array.isArray(inventory.images) ||
		inventory.images.some((image) => typeof image !== "string" || !/^[a-z0-9-]+$/.test(image))
	)
		throw new Error("malformed release image inventory");
	const configured = inventory.images;
	if (new Set(configured).size !== configured.length)
		throw new Error("release image inventory contains duplicates");
	if (!("upstream" in inventory) || !Array.isArray(inventory.upstream))
		throw new Error("malformed upstream image inventory");
	if (inventory.upstream.some((upstream) => !isUpstreamImage(upstream)))
		throw new Error("malformed upstream image inventory");
	return releaseImages(workflow).filter((image) => !configured.includes(image));
}

if (import.meta.main) {
	const inventory = JSON.parse(readFileSync("security/release-images.json", "utf8")) as unknown;
	const missing = validateInventory(
		inventory,
		readFileSync(".github/workflows/ci-docker-build.yml", "utf8"),
	);
	if (missing.length > 0)
		throw new Error(`release images missing from evidence inventory: ${missing.join(", ")}`);
	if (
		typeof inventory !== "object" ||
		inventory === null ||
		!("images" in inventory) ||
		!Array.isArray(inventory.images) ||
		!("upstream" in inventory) ||
		!Array.isArray(inventory.upstream)
	)
		throw new Error("malformed upstream image inventory");
	// The release-time validator in verify-release-evidence.ts rejects an inventory
	// without schemaVersion 1. Assert it here too, where every pull request runs:
	// otherwise the mismatch only surfaces mid-release, after the tag is cut.
	if (!("schemaVersion" in inventory) || inventory.schemaVersion !== 1)
		throw new Error("release image inventory must declare schemaVersion 1");
	const compose = [
		"docker/compose.app.yaml",
		"docker/compose.core.yaml",
		"docker/compose.proxy.yaml",
	]
		.map((path) => readFileSync(path, "utf8"))
		.join("\n");
	const knownImages = new Set(
		inventory.images.filter((image): image is string => typeof image === "string"),
	);
	for (const image of inventory.upstream) {
		if (!isUpstreamImage(image)) throw new Error("malformed upstream image inventory");
		knownImages.add(image.name);
	}
	const deployedImages = new Set<string>();
	for (const match of compose.matchAll(/^\s*image:\s*["']?([^\s"']+)/gm)) {
		const reference = match[1] ?? "";
		const variable = reference.match(/^\$\{HEPHAESTUS_IMAGE_([A-Z0-9_]+):\?/)?.[1];
		const name = variable?.toLowerCase().replaceAll("_", "-");
		if (!name || !knownImages.has(name))
			throw new Error(`deployed image does not consume the verified release lock: ${reference}`);
		deployedImages.add(name);
	}
	// Include agent-pi, which the application launches outside Compose.
	deployedImages.add("agent-pi");
	const extra = [...knownImages].filter((image) => !deployedImages.has(image));
	const absent = [...deployedImages].filter((image) => !knownImages.has(image));
	if (extra.length > 0 || absent.length > 0)
		throw new Error(
			`release inventory and production topology differ (extra: ${extra.join(", ") || "none"}; missing: ${absent.join(", ") || "none"})`,
		);
}
