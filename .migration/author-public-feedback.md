#### 🔴 Accept a new withholding reason in custom API clients

API responses can now include `PUBLIC_SUBJECT_INELIGIBLE` when saved public feedback does not meet the requirement to address the author’s work.

If a custom API client rejects unknown enum values, update it to accept `PUBLIC_SUBJECT_INELIGIBLE` before you upgrade.
The clients bundled with this release already accept this value.
