import { expect, loginAsDevAdmin, test } from "./fixtures";

test("a new practice group's visual editor opens", async ({ page }) => {
	await loginAsDevAdmin(page);
	await page.goto("/w/e2e/admin/practices");

	await expect(page.getByRole("heading", { name: "Practice setup" })).toBeVisible();
	await page.getByRole("button", { name: "Create group" }).click();
	const createDialog = page.getByRole("dialog", { name: "Create group" });
	await createDialog.getByRole("textbox", { name: "Name" }).fill("Code quality");
	await createDialog.getByRole("button", { name: /Edit the icon and color/u }).click();
	await expect(page.getByRole("dialog", { name: "Icon and color" })).toBeVisible();
});
