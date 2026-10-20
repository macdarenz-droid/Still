UPDATE catalogue_sources SET observation_id = ?, metadata_revision = ?, metadata_fingerprint = ?,
  source_uri = ?, display_name = ?, mime_type = ?, captured_at = ?, width = ?, height = ?, byte_length = ?,
  duration_ms = ?, album_id = ?, album_name = ?, orientation_degrees = ?, modified_at = ?,
  date_added_seconds = ?, generation_added = ?, generation_modified = ?
WHERE source_id = ? AND metadata_revision = ?;
