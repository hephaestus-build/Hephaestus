import { cn } from "cn";
import styles from "./HephIcon.module.css";

interface HephIconProps {
	size?: number;
	strokeWidth?: number;
	pad?: number;
	animated?: boolean;
	streaming?: boolean;
	className?: string;
	label?: string;
}

export function HephIcon({
	size = 16,
	strokeWidth = 2,
	pad = 2,
	animated = true,
	streaming = false,
	className,
	label,
}: HephIconProps) {
	return (
		<svg
			className={cn(styles.icon, animated && styles.animated, className)}
			height={size}
			width={size}
			viewBox={`-${pad} -${pad} ${24 + pad * 2} ${24 + pad * 2}`}
			fill="none"
			stroke="currentColor"
			strokeWidth={strokeWidth}
			strokeLinecap="round"
			strokeLinejoin="round"
			role={label ? "img" : undefined}
			aria-label={label}
			aria-hidden={label ? undefined : true}
		>
			<ellipse className={styles.shadow} cx="12" cy="23.6" rx="5" ry="1.2" fill="currentColor" />
			<g className={styles.float}>
				<g className={styles.wobble}>
					<line x1="12" y1="7.2" x2="12" y2="3.6" />
					<circle cx="12" cy="2.8" r="1" fill="currentColor" />
					<circle
						className={cn(styles.ping, streaming && styles.streaming)}
						cx="12"
						cy="2.8"
						r={streaming ? 4 : 1.6}
						fill="none"
						style={{ color: streaming ? "var(--color-mentor)" : undefined }}
						opacity={animated ? (streaming ? "1" : "0.5") : "0"}
					/>
					<rect x="4" y="8" width="16" height="12" rx="3" />
					<path d="M2 14h2" />
					<path d="M20 14h2" />
					<g className={styles.eyes}>
						<path d="M8.6 13.5h0.8" />
						<path d="M14.6 13.5h0.8" />
					</g>
					<path d="M9.7 16.8c1.1 1.1 3.5 1.1 4.6 0" />
					<path className={styles.blush} d="M6.8 15.2h0.6" />
					<path className={styles.blush} d="M16.6 15.2h0.6" />
				</g>
			</g>
		</svg>
	);
}
