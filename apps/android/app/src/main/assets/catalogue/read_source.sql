SELECT source_id, observation_id, metadata_revision, metadata_fingerprint FROM catalogue_sources
WHERE incarnation_id = ? AND is_video = ? AND external_handle = ? AND birth_marker = ?;
