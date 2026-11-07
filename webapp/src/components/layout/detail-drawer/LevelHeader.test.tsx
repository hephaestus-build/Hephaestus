import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { InLevelStack } from "@/stories/level-stack";
import { levelInfo } from "./level-info";
import { LevelHeader } from "./LevelHeader";

afterEach(cleanup);

it("opens the header's explanation without changing its title or the drawer's name", () => {
	render(
		<InLevelStack
			entry={{ kind: "practice", id: "describe-work" }}
			path={{ behind: [], onClose: vi.fn() }}
		>
			{(level) => (
				<LevelHeader
					{...level}
					current="Practice"
					title="Describe work"
					info={levelInfo({
						label: "About this practice",
						lead: "What the practice asks of your work.",
					})}
				/>
			)}
		</InLevelStack>,
	);
	const drawer = screen.getByRole("dialog", { name: "Describe work" });
	const description = document.getElementById(String(drawer.getAttribute("aria-describedby")));
	expect(description?.textContent).toContain("What the practice asks of your work.");
	fireEvent.click(screen.getByRole("button", { name: "About this practice" }));
	screen.getByRole("dialog", { name: "About this practice" });
	screen.getByRole("heading", { name: "Describe work" });
	screen.getByRole("dialog", { name: "Describe work" });
});
