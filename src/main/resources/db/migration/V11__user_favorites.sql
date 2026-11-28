CREATE TABLE tb_user_favorite (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  kind VARCHAR(8) COLLATE utf8mb4_bin NOT NULL,
  target_id BIGINT UNSIGNED NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_favorite_owner_target (user_id,kind,target_id),
  KEY idx_favorite_owner_list (user_id,kind,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
