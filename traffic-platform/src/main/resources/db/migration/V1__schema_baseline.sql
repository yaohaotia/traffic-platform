CREATE TABLE schema_metadata (
    metadata_key VARCHAR(64) NOT NULL PRIMARY KEY,
    metadata_value VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_metadata (metadata_key, metadata_value)
VALUES ('application', 'traffic-platform');
