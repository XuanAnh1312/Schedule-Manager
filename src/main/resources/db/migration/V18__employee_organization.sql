CREATE TABLE departments (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name NVARCHAR(100) NOT NULL UNIQUE
);
ALTER TABLE users ADD department_id VARCHAR(64) NULL,
    manager_id VARCHAR(64) NULL, archived_at DATETIME2(3) NULL,
    version INT NOT NULL CONSTRAINT df_users_version DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT fk_users_department FOREIGN KEY(department_id) REFERENCES departments(id),
    CONSTRAINT fk_users_manager FOREIGN KEY(manager_id) REFERENCES users(id),
    CONSTRAINT ck_users_manager_not_self CHECK(manager_id IS NULL OR manager_id <> id);
CREATE INDEX ix_users_organization ON users(branch_id,department_id,status);
