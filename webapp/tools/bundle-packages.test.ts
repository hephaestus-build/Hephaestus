import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { gzipSync } from "node:zlib";

import { build } from "vite-plus";
import { expect, it } from "vitest";
import { z } from "zod";

import {
	BUNDLE_LICENSE_FILE,
	BUNDLE_METADATA_FILE,
	bundlePackages,
	packageOf,
} from "./bundle-packages.ts";

it.each([
	["/repo/node_modules/.pnpm/recharts@3.8.0_x/node_modules/recharts/es6/index.js", "recharts"],
	[
		"/repo/node_modules/.pnpm/@tanstack+table-core@9.2.4/node_modules/@tanstack/table-core/build/index.js",
		"@tanstack/table-core",
	],
	[String.raw`C:\repo\node_modules\react-dom\index.js`, "react-dom"],
	["/repo/webapp/src/routes/__root.tsx", undefined],
	["\0rolldown/runtime.js", undefined],
])("reads module %s as package %s", (moduleId, name) => {
	expect(packageOf(moduleId)).toBe(name);
});

it("the bundle carries used and lazy package identities, without unused build dependencies", async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), "bundle-packages-"));
	try {
		await writeFile(path.join(root, "index.html"), '<script type="module" src="/app.js"></script>');
		await writeFile(
			path.join(root, "app.js"),
			'import { value } from "used"; console.log(value); window.lazy = () => import("@scope/lazy");',
		);
		for (const name of ["used", "@scope/lazy", "unused-build-tool"]) {
			const directory = path.join(root, "node_modules", name);
			await mkdir(directory, { recursive: true });
			await writeFile(
				path.join(directory, "package.json"),
				JSON.stringify({ name, version: "1.2.3", license: "MIT", main: "index.js" }),
			);
			await writeFile(path.join(directory, "index.js"), 'export const value = "fixture";');
		}
		await build({
			root,
			configFile: false,
			logLevel: "silent",
			plugins: [bundlePackages()],
			build: { license: { fileName: BUNDLE_LICENSE_FILE } },
		});
		const metadata = (name: string) =>
			path.join(root, "dist/.vite/packages", encodeURIComponent(`${name}@1.2.3`), "package.json");
		for (const name of ["used", "@scope/lazy"]) {
			assert.deepEqual(
				z.record(z.string(), z.string()).parse(JSON.parse(await readFile(metadata(name), "utf8"))),
				{
					name,
					version: "1.2.3",
					license: "MIT",
				},
			);
		}
		await assert.rejects(readFile(metadata("unused-build-tool")), { code: "ENOENT" });
		const inventoryPath = path.join(root, "dist", BUNDLE_METADATA_FILE);
		const inventoryText = await readFile(inventoryPath, "utf8");
		const inventory = z
			.object({
				schemaVersion: z.number(),
				packages: z.array(z.unknown()),
				chunks: z.array(
					z.object({
						fileName: z.string(),
						bytes: z.number(),
						gzipBytes: z.number(),
						isDynamicEntry: z.boolean(),
						facadeModule: z.string().nullable(),
						imports: z.array(z.string()),
						dynamicImports: z.array(z.string()),
						packages: z.array(z.string()),
					}),
				),
			})
			.parse(JSON.parse(inventoryText));
		assert.equal(inventory.schemaVersion, 1);
		assert.equal(inventory.packages.length, 2);
		const { chunks } = inventory;
		assert.ok(chunks.some((chunk) => chunk.isDynamicEntry));
		for (const chunk of chunks) {
			const code = await readFile(path.join(root, "dist", chunk.fileName));
			assert.equal(chunk.bytes, code.byteLength);
			assert.equal(chunk.gzipBytes, gzipSync(code, { level: 6 }).byteLength);
		}
		assert.deepEqual([...new Set(chunks.flatMap((chunk) => chunk.packages))].sort(), [
			"@scope/lazy",
			"used",
		]);
		const outputPaths = new Set(chunks.map((chunk) => chunk.fileName));
		for (const chunk of chunks) {
			assert.ok([...chunk.imports, ...chunk.dynamicImports].every((file) => outputPaths.has(file)));
		}
		const facades = chunks
			.map((chunk) => chunk.facadeModule)
			.filter((value) => typeof value === "string");
		for (const facade of facades) {
			assert.equal(path.isAbsolute(facade), false);
		}
		assert.equal(
			chunks.find((chunk) => chunk.isDynamicEntry)?.facadeModule,
			"node_modules/@scope/lazy/index.js",
		);

		assert.equal(inventoryText.includes(root), false);
		await assert.rejects(readFile(path.join(root, "dist", BUNDLE_LICENSE_FILE)), {
			code: "ENOENT",
		});
		await assert.rejects(
			build({
				root,
				configFile: false,
				logLevel: "silent",
				plugins: [
					{
						name: "omit-native-license",
						writeBundle: {
							order: "pre",
							handler(_options, bundle) {
								const asset = bundle[BUNDLE_LICENSE_FILE];
								assert.ok(asset?.type === "asset");
								assert.ok(typeof asset.source === "string");
								const text = asset.source;
								const entries = z
									.array(z.looseObject({ name: z.string() }))
									.parse(JSON.parse(text));
								asset.source = JSON.stringify(entries.filter((item) => item.name !== "used"));
							},
						},
					},
					bundlePackages(),
				],
				build: { license: { fileName: BUNDLE_LICENSE_FILE } },
			}),
			/license report omitted bundled package used/u,
		);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
