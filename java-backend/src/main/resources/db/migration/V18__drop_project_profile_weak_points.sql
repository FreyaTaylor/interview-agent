-- V18: 项目画像移除 weak_points（不保留历史兼容）
ALTER TABLE project_user_profile
DROP COLUMN weak_points;
