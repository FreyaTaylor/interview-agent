-- V21: S8 语音转写角色归一化 prompt seed
-- - interview/asr-role-normalize

INSERT INTO prompt_template (key, content, description) VALUES
('interview/asr-role-normalize', $PROMPT$下面是一段面试录音转写文本，按“说话人X：内容”分行。
请判断：哪个说话人是【面试官】，哪个是【候选人/我】。

判定依据：
1) 面试官通常主动提问、引导话题、让候选人展开。
2) 候选人通常回答问题、描述自己的项目经历与做法。

只输出 JSON，不要任何解释文字：
{
  "interviewer": "说话人X",
  "candidate": "说话人Y"
}

文本：
{snippet}
$PROMPT$, 'S8 面试复盘：ASR 说话人角色归一化')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
