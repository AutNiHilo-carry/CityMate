CREATE TABLE tb_knowledge_source (
  source_key VARCHAR(80) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  current_version INT NOT NULL DEFAULT 0,
  enabled TINYINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tb_knowledge_revision (
  source_key VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  version INT NOT NULL,
  title VARCHAR(160) NOT NULL,
  kind VARCHAR(24) NOT NULL,
  shop_id BIGINT NULL,
  voucher_id BIGINT NULL,
  rule_fingerprint VARCHAR(64) NULL,
  content TEXT NOT NULL,
  valid_from DATETIME(3) NOT NULL,
  valid_until DATETIME(3) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(source_key,version),
  KEY idx_knowledge_scope(kind,shop_id,voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tb_knowledge_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_key VARCHAR(80) COLLATE utf8mb4_bin NOT NULL,
  version INT NOT NULL,
  chunk_no INT NOT NULL,
  content TEXT NOT NULL,
  UNIQUE KEY uq_knowledge_chunk(source_key,version,chunk_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
