import { screen, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";

/**
 * Moves from workspace `from` to workspace `to` as a reader does: through the switcher menu in the
 * sidebar. The switcher has no keyboard shortcut, because the browser uses Cmd or Ctrl with a number
 * to switch tabs.
 */
export async function switchWorkspace(user: UserEvent, from: string, to: string): Promise<void> {
	await user.click(screen.getByRole("button", { name: from }));
	const menu = within(await screen.findByRole("menu"));
	await user.click(menu.getByRole("menuitem", { name: to }));
}
