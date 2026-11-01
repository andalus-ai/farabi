# Local file ingestion adapter

`farabi-adapter-file` provides a development/single-node implementation of Farabi's source-intake and profiler ports.

Supported inputs:
- CSV and TSV (UTF-8, header row, quoted delimiters and escaped quotes)
- JSON object or array of objects
- JSONL (one object per line)

Safety and operational behavior:
- Caller authorization remains the responsibility of `DatasetImportService` and runs before staging.
- Upload bytes are streamed to a temporary file, bounded by configured byte limits, checked against declared content length, and atomically moved into a workspace-hashed directory.
- Staged references are opaque UUID references and are checked against the requested workspace during profiling.
- Profiling enforces row, column and distinct-value limits; it returns metadata only.
- Client filenames are never used as filesystem paths.

CSV multiline quoted fields, XLSX, Parquet, malware scanning, encryption-at-rest, retention cleanup and distributed object storage are intentionally not claimed by this adapter. Use a production object-storage adapter for multi-node deployments and add format-specific readers behind the same ports.
