import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { MoneyCell } from "./MoneyCell";

describe("MoneyCell", () => {
	it("prints a figure exactly as it was formatted, and a dash where there is none", () => {
		render(
			<table>
				<tbody>
					<tr>
						<td>
							<MoneyCell>$0.00</MoneyCell>
						</td>
						<td>
							<MoneyCell>$0.0077</MoneyCell>
						</td>
						<td>
							<MoneyCell>{null}</MoneyCell>
						</td>
					</tr>
				</tbody>
			</table>,
		);

		expect(screen.getAllByRole("cell").map((cell) => cell.textContent)).toStrictEqual([
			"$0.00",
			"$0.0077",
			"—",
		]);
	});
});
