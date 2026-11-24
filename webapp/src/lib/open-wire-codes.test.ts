import { expect, it } from "vite-plus/test";

import { zAboutRole, zIntegrationFamily, zLabel } from "@/api/zod.gen";

it("decodes future enum and label codes with the generated schemas", () => {
	const label = {
		code: "notice.future-code",
		params: { place: { type: "future-param-kind" } },
		text: "A new notice is available.",
	};
	expect(zAboutRole.parse("APPROVER")).toBe("APPROVER");
	expect(zIntegrationFamily.parse("PLANNING")).toBe("PLANNING");
	expect(zLabel.parse(label)).toStrictEqual(label);
});
