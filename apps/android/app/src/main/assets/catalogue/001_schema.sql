-- Local metadata observations only. Compatible with Android API 26 SQLite.
-- The UI must never use these retained rows as authority for visibility/access.
CREATE TABLE catalogue_incarnations (
  incarnation_id TEXT PRIMARY KEY NOT NULL,
  provider_authority TEXT NOT NULL,
  volume_name TEXT NOT NULL,
  provider_version TEXT
);
CREATE TABLE catalogue_namespaces (
  provider_authority TEXT NOT NULL,
  volume_name TEXT NOT NULL,
  incarnation_id TEXT NOT NULL REFERENCES catalogue_incarnations(incarnation_id),
  provider_version TEXT,
  last_generation INTEGER CHECK(last_generation IS NULL OR last_generation >= 0),
  PRIMARY KEY(provider_authority, volume_name)
);
CREATE TABLE catalogue_sources (
  source_id TEXT PRIMARY KEY NOT NULL,
  incarnation_id TEXT NOT NULL REFERENCES catalogue_incarnations(incarnation_id),
  is_video INTEGER NOT NULL CHECK(is_video IN (0, 1)),
  external_handle TEXT NOT NULL,
  birth_marker TEXT NOT NULL,
  observation_id TEXT UNIQUE NOT NULL,
  metadata_revision INTEGER NOT NULL CHECK(metadata_revision > 0),
  metadata_fingerprint TEXT NOT NULL,
  source_uri TEXT NOT NULL,
  display_name TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  captured_at INTEGER NOT NULL CHECK(captured_at >= 0),
  width INTEGER NOT NULL CHECK(width >= 0),
  height INTEGER NOT NULL CHECK(height >= 0),
  byte_length INTEGER NOT NULL CHECK(byte_length >= 0),
  duration_ms INTEGER NOT NULL CHECK(duration_ms >= 0),
  album_id TEXT NOT NULL,
  album_name TEXT NOT NULL,
  orientation_degrees INTEGER NOT NULL CHECK(orientation_degrees IN (0, 90, 180, 270)),
  modified_at INTEGER NOT NULL CHECK(modified_at >= 0),
  date_added_seconds INTEGER NOT NULL CHECK(date_added_seconds >= 0),
  generation_added INTEGER CHECK(generation_added IS NULL OR generation_added >= 0),
  generation_modified INTEGER CHECK(generation_modified IS NULL OR generation_modified >= 0),
  UNIQUE(incarnation_id, is_video, external_handle, birth_marker)
);
CREATE INDEX catalogue_source_capture ON catalogue_sources(captured_at, source_id);
