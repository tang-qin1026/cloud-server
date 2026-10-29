-- 存量企业管理员的 data_scope 修正为 all（2026-10-29）
--
-- 背景：影子用户建档时 data_scope 走实体默认值 self。
-- 企业管理员若停留在 self，他只能看到自己的文件与数据，
-- 与"企业管理员"这个身份的预期不符（本企业内应当全权）。
--
-- 上限仍由 enterprise_id 兜住：all 只在本企业内生效，跨企业依然看不到。
--
-- 只改 enterprise_role = 1 且当前为 self 的行：
-- 不动已经手工调成 dept / dept_below / dept_subtree 的管理员（那是刻意配置）。

UPDATE users
   SET data_scope = 'all',
       updated_at = now()
 WHERE enterprise_role = 1
   AND data_scope = 'self';