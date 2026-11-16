import { CalloutContainer as Container } from "fumadocs-ui/components/callout";
import type { ComponentProps } from "react";

export { CalloutDescription, CalloutTitle } from "fumadocs-ui/components/callout";

/*
 * Each callout has one meaning in the writing standard (§ Warnings, cautions, and notes). The `callouts` plugin gives
 * each one its label at build time; this maps it to the look of a Fumadocs callout.
 */
const LOOK = { note: "info", tip: "idea", caution: "warning" } as const;

const isKind = (type: string | undefined): type is keyof typeof LOOK =>
	type !== undefined && Object.hasOwn(LOOK, type);

export function CalloutContainer({ type, ...props }: ComponentProps<"div"> & { type?: string }) {
	return <Container type={LOOK[isKind(type) ? type : "note"]} {...props} />;
}
