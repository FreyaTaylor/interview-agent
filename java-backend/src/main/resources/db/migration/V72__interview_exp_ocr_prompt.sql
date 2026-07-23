-- =============================================================================
-- V72: 面经解析「图片 OCR」提示词种子（interview-exp/ocr）
--
-- 背景：面经常以截图/长图分享，需先把图里文字原样转录成纯文本，再走 interview-exp/parse。
-- 约定：只转录、不改写、不总结；保留问题分行与顺序；用 qwen-vl-max 视觉模型。
-- =============================================================================

INSERT INTO prompt_template (key, content, description) VALUES
('interview-exp/ocr', $PROMPT$请把这张「面经」截图里的所有文字**原样转录**成纯文本：
- 保留问题的分行与先后顺序；
- 只转录，**不要**改写、翻译、总结或补充；
- 不要输出任何解释说明，只输出转录得到的文本。$PROMPT$,
'面经解析：图片 OCR，把面经截图文字原样转录为纯文本（qwen-vl-max）')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
