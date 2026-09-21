// React's `CSSProperties` comes from csstype, which lists only standard properties, so a style
// object carrying a CSS custom property does not fit it. The template-literal index signature is
// narrow enough that a key which is neither a standard property nor a custom property is still
// rejected — an index signature of plain `string` would accept any key at all, so a misspelled
// property name would type-check and silently do nothing.
import "react";

declare module "react" {
	interface CSSProperties {
		// oxlint-disable-next-line typescript/consistent-indexed-object-style -- an augmentation merges into csstype's interface; a `Record` alias would replace it.
		[custom: `--${string}`]: string | number | undefined;
	}
}
