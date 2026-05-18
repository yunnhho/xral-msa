-- Social-only members have no password; allow NULL
ALTER TABLE members MODIFY COLUMN password_hash VARCHAR(255) NULL;
