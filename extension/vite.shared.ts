import path from "node:path";

import tailwindcss from "@tailwindcss/vite";
import viteReact from "@vitejs/plugin-react";
import type { PluginOption } from "vite";

/** Shared pure webapp modules keep their own import paths; WXT's default `@` stays local. */
export const extensionAliases: Record<string, string> = {
	"@/components": path.resolve(import.meta.dirname, "../webapp/src/components"),
	"@/lib": path.resolve(import.meta.dirname, "../webapp/src/lib"),
	// The canonical Heph mark (`docs/contributor/brand-assets.mdx`), bundled rather than copied.
	"@/brand": path.resolve(import.meta.dirname, "../webapp/brand"),
	"@/api": path.resolve(import.meta.dirname, "src/api"),
	"~": path.resolve(import.meta.dirname, "src"),
};

/** The extension and its previews use the same React Compiler and Tailwind processing. */
export function extensionVitePlugins(): PluginOption[] {
	return [viteReact({ compiler: true }), tailwindcss()];
}
