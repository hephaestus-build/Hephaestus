#### 🔴 Update API clients that read paged lists

This release has two page shapes. A client that still reads an old field gets no value for it.

A numbered list returns `{content, page, size, totalElements, totalPages}`:

- These fields are gone: `number`, `numberOfElements`, `first`, `last`, `empty`, `sort` and `pageable`. Read `page` for `number`. There is another page when `page + 1 < totalPages`.
- `GET /admin/product-feedback` moves `page.number`, `page.size`, `page.totalElements` and `page.totalPages` to the top level.
- `GET /admin/users` returns this object, not a bare array.

A long append-only list returns `{content, nextCursor}`. Pass `nextCursor` as `cursor` to read the next page. The last page has no `nextCursor`.

| Endpoint | Change |
| --- | --- |
| `GET /admin/audit` and `GET /admin/config-audit` | Replace `page` with `cursor`. The response is a cursor page, so `size`, `totalElements`, `totalPages` and the numbered-page fields are gone. For the total, read `/count` under the same path with the same filters. |
| `GET /workspaces/{workspaceSlug}/config-audit` | The same change, and the same `/count` read. |
| `GET /admin/integration/dead-letters` and `GET /workspaces/{workspaceSlug}/integrations/dead-letters` | The bare array becomes a cursor page. Replace `after` with `cursor`. |
| `GET /workspaces/{workspaceSlug}/practices/scans/{scanId}/report/items` and `/problems` | `hasNext` is gone. There is another page when `nextCursor` is present. |
| `GET /workspaces/{workspaceSlug}/mentor/entries` | `entries` becomes `content`, and `earlierBefore` becomes `nextCursor`. Replace `before` with `cursor`, and `limit` with `size`. |
| `GET /workspaces/{workspaceSlug}/mentor/entries/{entryId}/thread` | The same parameters. `entries` and `earlierBefore` move into `replies`, as `replies.content` and `replies.nextCursor`. |
| `POST /workspaces/gitlab/projects` | `projects` becomes `content`, and `nextPage` becomes `nextCursor`. Replace `page` with `cursor`. |
