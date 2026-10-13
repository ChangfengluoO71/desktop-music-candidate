# Upstream provenance

This candidate reuses selected code from [`qingyueyin/Hachimi`](https://github.com/qingyueyin/Hachimi), pinned to commit [`fafbc5e12ef4ce123a7e36a6a9e9d4553109ef91`](https://github.com/qingyueyin/Hachimi/tree/fafbc5e12ef4ce123a7e36a6a9e9d4553109ef91).

## Copied API sources

The following files were copied byte-for-byte from the pinned source tree into this project's `src/main/kotlin` tree. They have no Android imports and are the only source files copied from the Android module for the standalone build:

- `app/src/main/java/com/qing/hachimi/data/api/NeteaseApi.kt`
- `app/src/main/java/com/qing/hachimi/data/api/NeteaseCrypto.kt`
- `app/src/main/java/com/qing/hachimi/data/api/SongApi.kt`

The root `LICENSE` preserves the upstream MIT license text and copyright notice. The upstream file also contains app-specific disclaimer text after the MIT license; that material is not part of the MIT grant and is not copied into this candidate's license terms. Any product-specific notices should be reviewed and maintained separately from open-source license terms.

The upstream API sources retain their package names, implementation and existing source text; no attribution header was removed. No restricted Hachimi logo or official icon was copied.

## Desktop candidate sources

The Compose Desktop application, local music library, download queue, Windows credential integration, tests and synthetic audio fixtures were copied from the existing untracked `desktop/` work directory into this independent candidate. Those files were not part of the pinned upstream Git commit, so this document does not claim they were present in upstream. Their existing behaviors and tests are preserved, apart from the narrowly scoped placeholder product identity changes documented in `docs/PRODUCT-IDENTITY.md`.

No Android UI, Activity, Android Service or unrelated application source is included in this candidate.