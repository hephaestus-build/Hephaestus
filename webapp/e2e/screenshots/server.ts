// Not the dev server's port, so a capture runs beside `vp run dev`.
export const PORT = process.env.DOCS_SCREENSHOTS_PORT ?? "4791";
export const BASE_URL = `http://127.0.0.1:${PORT}`;
