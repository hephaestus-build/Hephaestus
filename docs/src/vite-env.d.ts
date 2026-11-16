/// <reference types="vite/client" />

interface ImportMetaEnv {
	readonly DOCS_CHANNEL: "latest" | "next";
	readonly DOCS_RELEASE: string;
}

declare module "virtual:docs-page-tree" {
	import type { SerializedPageTree } from "fumadocs-core/source/client";

	const tree: SerializedPageTree;
	export default tree;
}

declare module "virtual:migration-entries" {
	import type { MigrationGuide } from "@/lib/migration-entries";

	const guide: MigrationGuide;
	export default guide;
}
