# Security

BackupKit moves a user's files into that user's own cloud account. A vulnerability here is a vulnerability in every app that uses it, so please report privately.

- Email founder@vaazhstudios.com with steps to reproduce. Expect an acknowledgement within three days.
- Please give us 90 days before public disclosure; we credit reporters in the changelog unless asked not to.
- In scope: anything that lets one app or account read, alter or delete another's backup, or that leaks file contents outside the user's cloud. Out of scope: the cloud providers themselves, and apps that misuse the API.

Supported: the latest minor version. Fixes ship as a patch release and a note in `CHANGELOG.md`.
