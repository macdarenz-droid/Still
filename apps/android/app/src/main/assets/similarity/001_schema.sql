-- Rebuildable visual descriptors only. No names, paths, URIs, bytes or content hashes.
CREATE TABLE similarity_index_meta (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
);
INSERT INTO similarity_index_meta(key, value) VALUES
  ('application', 'still-local-similarity-index-v1'),
  ('schema-version', '1');
CREATE TABLE similarity_index_namespaces (
  namespace_key TEXT PRIMARY KEY NOT NULL CHECK(length(namespace_key) = 64 AND namespace_key NOT GLOB '*[^0-9a-f]*'),
  provider_version_digest TEXT NOT NULL CHECK(length(provider_version_digest) = 64 AND provider_version_digest NOT GLOB '*[^0-9a-f]*'),
  provider_generation INTEGER CHECK(provider_generation IS NULL OR provider_generation >= 0),
  incarnation TEXT NOT NULL CHECK(length(incarnation) = 64 AND incarnation NOT GLOB '*[^0-9a-f]*')
);
CREATE TABLE similarity_features (
  namespace_key TEXT NOT NULL REFERENCES similarity_index_namespaces(namespace_key) ON DELETE CASCADE,
  namespace_incarnation TEXT NOT NULL CHECK(length(namespace_incarnation) = 64 AND namespace_incarnation NOT GLOB '*[^0-9a-f]*'),
  source_key TEXT PRIMARY KEY NOT NULL CHECK(length(source_key) = 64 AND source_key NOT GLOB '*[^0-9a-f]*'),
  source_revision TEXT NOT NULL CHECK(length(source_revision) = 64 AND source_revision NOT GLOB '*[^0-9a-f]*'),
  pipeline_version TEXT NOT NULL CHECK(length(pipeline_version) BETWEEN 1 AND 96),
  perceptual_hash INTEGER NOT NULL,
  descriptor BLOB NOT NULL CHECK(typeof(descriptor) = 'blob' AND length(descriptor) = 136)
);
CREATE TABLE similarity_hash_buckets (
  source_key TEXT NOT NULL REFERENCES similarity_features(source_key) ON DELETE CASCADE,
  pipeline_version TEXT NOT NULL CHECK(length(pipeline_version) BETWEEN 1 AND 96),
  block_number INTEGER NOT NULL CHECK(block_number BETWEEN 0 AND 10),
  block_value INTEGER NOT NULL CHECK(block_value BETWEEN 0 AND 63),
  PRIMARY KEY(source_key, block_number)
);
CREATE INDEX similarity_hash_bucket_lookup ON similarity_hash_buckets(pipeline_version, block_number, block_value, source_key);
