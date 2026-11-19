SELECT incarnation_id, provider_version, last_generation FROM catalogue_namespaces
WHERE provider_authority = ? AND volume_name = ?;
