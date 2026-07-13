-- V56: interview/parse 收紧 group 的 turn_ids 范围
-- 背景：原规则只强调「必须包含所有提问+回答、不要遗漏」，模型倾向把话题之间的寒暄/过渡/
--        上一话题收尾 turn 也并进当前 group（尤其编号连续时），导致 group 的 turn_ids 过宽。
-- 现象：record 6「虚拟线程与 synchronized」组 turn_ids=[201..212]，但 201-209 是薪资闲聊，
--        真正提问在 210-212 →「定位原文」跳到 201 落在无关处。
-- 修复：范围须紧贴本话题（从面试官首次提出该主问起、到本话题最后一次追问/回答止），
--        严禁并入话题间寒暄/过渡/上一话题收尾/无关闲聊 turn（即使编号连续）。
-- 手段：对 turn_ids 说明行做单行定点 replace。

UPDATE prompt_template SET content =
  replace(content,
    '必须包含该话题的所有面试官提问 + 候选人回答 turn，不要遗漏追问/回答。turn_ids 必须按升序排列',
    '范围须「紧贴本话题」：从面试官首次提出该主问的 turn 开始，到本话题最后一次追问/回答结束；只包含属于本话题的面试官提问 + 候选人回答 + 追问 turn。严禁把话题之间的寒暄/过渡/上一话题收尾/无关闲聊 turn 并进来（即使编号连续）。宁可少纳几个无关 turn，也不要让范围越界到别的话题。turn_ids 必须按升序排列')
WHERE key = 'interview/parse';
