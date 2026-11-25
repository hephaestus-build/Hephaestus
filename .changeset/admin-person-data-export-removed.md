---
"hephaestus": minor
---

An instance admin can no longer download a person's data. **Breaking:** the **Download JSON export** button on **Instance administration → Person data** and `POST /api/admin/person-data/{id}/export` are gone. A person answers their own access request with **User settings → Export your data**. The person-data preview still shows how many rows each store holds, never their content, and erasure works as before.

**Operators:** Stop scripts that call the admin person-data export. Follow **Point access requests to the person's own export** in the upgrade guide.
