// Write docs/admin/configuration-reference.mdx from the sources and the configuration metadata of the
// executable JAR: the CI artifact when HEPHAESTUS_APPLICATION_JAR names it, a local build otherwise.
// The JAR carries the metadata of the application and of every library, Spring Boot's included.
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

import { unzipSync } from "fflate";

import { executableJar } from "./lib/application-jar.ts";
import {
	join,
	Metadata,
	type MetadataProperty,
	readSources,
	REFERENCE_PAGE,
	renderPage,
	REPO_ROOT,
} from "./lib/configuration-reference.ts";
import { isRecord, parseJson } from "./lib/json.ts";

const METADATA_FILES = [
	"META-INF/spring-configuration-metadata.json",
	"META-INF/additional-spring-configuration-metadata.json",
];

function metadataProperties(json: Uint8Array): MetadataProperty[] {
	const document = parseJson(new TextDecoder().decode(json));
	if (!isRecord(document) || !Array.isArray(document.properties)) {
		return [];
	}
	return document.properties.flatMap((property: unknown) =>
		isRecord(property) && typeof property.name === "string"
			? [
					{
						name: property.name,
						type: typeof property.type === "string" ? property.type : undefined,
						description:
							typeof property.description === "string" ? property.description : undefined,
						defaultValue: property.defaultValue,
					},
				]
			: [],
	);
}

/**
 * The application's own metadata first, so its descriptions win over a library's for one name.
 * Spring Boot's Gradle plugin moves the application's `META-INF` to the root of the JAR.
 */
function readJarMetadata(jar: Uint8Array): MetadataProperty[] {
	const outer = unzipSync(jar, {
		filter: (file) => file.name.startsWith("BOOT-INF/lib/") || METADATA_FILES.includes(file.name),
	});
	const properties: MetadataProperty[] = [];
	for (const name of METADATA_FILES) {
		const own = outer[name];
		if (own) {
			properties.push(...metadataProperties(own));
		}
	}
	if (properties.length === 0) {
		throw new Error("The JAR carries no application configuration metadata");
	}
	for (const [name, library] of Object.entries(outer)) {
		if (!name.endsWith(".jar")) {
			continue;
		}
		const inner = unzipSync(library, { filter: (file) => METADATA_FILES.includes(file.name) });
		for (const file of METADATA_FILES) {
			const json = inner[file];
			if (json) {
				properties.push(...metadataProperties(json));
			}
		}
	}
	return properties;
}

const jar = await executableJar(process.argv.slice(2));
const metadata = new Metadata(readJarMetadata(await readFile(jar)));
const { rows, unbound, unread } = join(await readSources(), metadata);

let failed = false;
if (unbound.length > 0) {
	failed = true;
	console.error("Compose hands these variables to a Java container, and they bind no setting:");
	for (const entry of unbound) {
		console.error(`  ${entry}`);
	}
	console.error("Rename each to the variable its setting reads, or remove it.\n");
}
if (unread.length > 0) {
	failed = true;
	console.error("No Compose file and no application YAML reads these .env.example keys:");
	for (const entry of unread) {
		console.error(`  ${entry}`);
	}
	console.error("Remove each key, or forward it to the setting it is meant for.\n");
}

const page = path.join(REPO_ROOT, REFERENCE_PAGE);
await writeFile(page, renderPage(rows));
console.log(`Wrote ${REFERENCE_PAGE}: ${rows.length} variables.`);
const undescribed = rows.filter((row) => row.description === "").map((row) => row.variable);
if (undescribed.length > 0) {
	console.error(`${undescribed.length} rows have no description: ${undescribed.join(", ")}`);
}
if (failed) {
	process.exitCode = 1;
}
