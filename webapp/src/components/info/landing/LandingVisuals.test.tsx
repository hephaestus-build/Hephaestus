import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";

import { LandingCluster } from "./LandingVisuals";

it.each([
	{ matches: true, opacity: "", transform: "" },
	{ matches: false, opacity: "0", transform: "translateY(22px)" },
])(
	"respects reduced-motion=$matches before the first animation frame",
	({ matches, opacity, transform }) => {
		const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
		using query = vi
			.spyOn(window, "matchMedia")
			.mockReturnValue(Object.assign(mediaQuery, { matches }));
		// The viewport observer only starts animations after intersection; this test checks the
		// initial style the reader sees before that callback, not jsdom's nonexistent layout.
		vi.stubGlobal(
			"IntersectionObserver",
			class {
				observe() {}
				unobserve() {}
				disconnect() {}
			},
		);
		try {
			render(
				<ol>
					<LandingCluster placement={{ column: 1, row: 1 }} delay={0}>
						Practice feedback
					</LandingCluster>
				</ol>,
			);
			const cluster = screen.getByRole("listitem");
			expect(cluster.style.opacity).toBe(opacity);
			expect(cluster.style.transform).toBe(transform);
			expect(query).toHaveBeenCalledWith("(prefers-reduced-motion: reduce)");
		} finally {
			vi.unstubAllGlobals();
		}
	},
);
