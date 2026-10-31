import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

import type { Plugin } from "vite";
import { z } from "zod";

import { packageOf } from "./entry-chunk/entry-closure.ts";

// Vite documents JSON output, but not its fields. Reject a changed native shape before publication.
const licenses = z
	.array(
		z.object({
			name: z.string().min(1),
			version: z.string().min(1),
			identifier: z.string().optional(),
			text: z.string().optional(),
		}),
	)
	.min(1);

export const BUNDLE_LICENSE_FILE = ".vite/license.json";
export const BUNDLE_METADATA_FILE = ".vite/bundle-metadata.json";

/** One final bundle inventory for Syft's package manifests and the byte budget. */
export function bundlePackages(): Plugin {
	let root = "";
	let directory = "";
	return {
		name: "bundle-packages",
		apply: "build",
		configResolved(config) {
			root = config.root;
			directory = path.resolve(root, config.build.outDir);
		},
		writeBundle: {
			order: "post",
			handler(_options, bundle) {
				const asset = bundle[BUNDLE_LICENSE_FILE];
				if (asset?.type !== "asset") {
					throw new Error("Vite did not emit the bundled package licenses");
				}
				const source =
					typeof asset.source === "string" ? asset.source : new TextDecoder().decode(asset.source);
				const metadata = {
					schemaVersion: 1,
					packages: licenses
						.parse(JSON.parse(source))
						.map(({ name, version, identifier, text }) => ({
							name,
							version,
							license: identifier,
							licenseText: text,
						})),
					chunks: Object.values(bundle)
						.flatMap((chunk) =>
							chunk.type === "chunk"
								? [
										{
											fileName: chunk.fileName,
											isEntry: chunk.isEntry,
											isDynamicEntry: chunk.isDynamicEntry,
											facadeModule:
												chunk.facadeModuleId === null
													? null
													: path.relative(root, chunk.facadeModuleId).split(path.sep).join("/"),
											imports: chunk.imports,
											dynamicImports: chunk.dynamicImports,
											bytes: Buffer.byteLength(chunk.code),
											gzipBytes: gzipSync(chunk.code, { level: 6 }).byteLength,
											packages: [
												...new Set(chunk.moduleIds.flatMap((id) => packageOf(id) ?? [])),
											].sort(),
										},
									]
								: [],
						)
						.sort((left, right) => {
							if (left.fileName < right.fileName) {
								return -1;
							}
							if (left.fileName > right.fileName) {
								return 1;
							}
							return 0;
						}),
				};
				const known = new Set(metadata.packages.map((item) => item.name));
				for (const name of metadata.chunks.flatMap((chunk) => chunk.packages)) {
					if (!known.has(name)) {
						throw new Error(`Vite's license report omitted bundled package ${name}`);
					}
				}

				// Vite expands preload maps after generateBundle; writeBundle sees the final JavaScript.
				mkdirSync(path.join(directory, ".vite"), { recursive: true });
				writeFileSync(
					path.join(directory, BUNDLE_METADATA_FILE),
					`${JSON.stringify(metadata, null, 2)}\n`,
				);
				const packages = path.join(directory, ".vite/packages");
				rmSync(packages, { recursive: true, force: true });
				for (const { name, version, license } of metadata.packages) {
					const target = path.join(packages, encodeURIComponent(`${name}@${version}`));
					mkdirSync(target, { recursive: true });
					writeFileSync(
						path.join(target, "package.json"),
						`${JSON.stringify({ name, version, license })}\n`,
					);
				}
				rmSync(path.join(directory, BUNDLE_LICENSE_FILE));
			},
		},
	};
}
