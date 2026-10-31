import { cn } from "cn";
import styles from "./HephaestusMark.module.css";

import type { BrandIcon } from "@/components/icons/brand";
import { hasText } from "@/lib/text";

/** Hephaestus's name as the mark carries it, where the mark stands for Hephaestus rather than beside the word. */
export const ASK_HEPHAESTUS_LABEL = "Ask Hephaestus";

interface HephaestusMarkProps {
	size?: number;
	strokeWidth?: number;
	pad?: number;
	className?: string;
	label?: string;
}

export function HephaestusMark({
	size = 16,
	strokeWidth = 2,
	pad = 2,
	className,
	label,
}: HephaestusMarkProps) {
	return (
		<svg
			className={cn(styles.icon, className)}
			style={{ "--mark-size": `${size}px` }}
			viewBox={`-${pad} -${pad} ${24 + pad * 2} ${24 + pad * 2}`}
			fill="none"
			stroke="currentColor"
			strokeWidth={strokeWidth}
			strokeLinecap="round"
			strokeLinejoin="round"
			role={hasText(label) ? "img" : undefined}
			aria-label={label}
			aria-hidden={hasText(label) ? undefined : true}
		>
			<ellipse
				className="opacity-8 dark:opacity-15"
				cx="12"
				cy="23.6"
				rx="5"
				ry="1.2"
				fill="currentColor"
			/>
			<line x1="12" y1="7.2" x2="12" y2="3.6" />
			<circle cx="12" cy="2.8" r="1" fill="currentColor" />
			<rect x="4" y="8" width="16" height="12" rx="3" />
			<path d="M2 14h2" />
			<path d="M20 14h2" />
			<path d="M8.6 13.5h0.8" />
			<path d="M14.6 13.5h0.8" />
			<path d="M9.7 16.8c1.1 1.1 3.5 1.1 4.6 0" />
			<path className="opacity-25 dark:opacity-20" d="M6.8 15.2h0.6" />
			<path className="opacity-25 dark:opacity-20" d="M16.6 15.2h0.6" />
		</svg>
	);
}

/**
 * Hephaestus's mark at rest, in the slot of a Lucide or brand icon: a badge, a facet option or a registry
 * entry. The module owns its size, so it is set here rather than by the slot's `size-*` utility.
 */
function MarkAtRest({ className }: Parameters<BrandIcon>[0]) {
	return <HephaestusMark size={14} pad={1} className={className} />;
}

export const HephaestusMarkIcon: BrandIcon = MarkAtRest;
