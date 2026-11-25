#### 🔴 Point access requests to the person's own export

- **When:** before the window
- **Action:** Stop scripts that call `POST /api/admin/person-data/{id}/export`, and send access requests to the person's own export.

Follow the [person-data export upgrade note](https://docs.hephaestus.build/admin/upgrade-notes/admin-person-data-export).
