CREATE TABLE identity_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_identity_username UNIQUE (username)
);

CREATE TABLE identity_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    CONSTRAINT uk_identity_role_code UNIQUE (code)
);

CREATE TABLE identity_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    CONSTRAINT uk_identity_permission_code UNIQUE (code)
);

CREATE TABLE identity_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES identity_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES identity_role (id)
);

CREATE TABLE identity_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES identity_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES identity_permission (id)
);

CREATE TABLE identity_region (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uk_identity_region_code UNIQUE (code)
);

CREATE TABLE identity_user_region (
    user_id BIGINT NOT NULL,
    region_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, region_id),
    CONSTRAINT fk_user_region_user FOREIGN KEY (user_id) REFERENCES identity_user (id),
    CONSTRAINT fk_user_region_region FOREIGN KEY (region_id) REFERENCES identity_region (id)
);
