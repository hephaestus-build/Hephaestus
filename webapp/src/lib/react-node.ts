import type { ReactNode } from "react";

/**
 * Whether React would paint anything for `node`. `null`, `undefined` and the booleans render
 * nothing and `""` renders an empty text run, so a wrapper gated on any of them would be an
 * empty box; `0` does render, which is what a truthiness check gets wrong.
 */
export function rendersContent(node: ReactNode): boolean {
	return node != null && node !== false && node !== true && node !== "";
}
