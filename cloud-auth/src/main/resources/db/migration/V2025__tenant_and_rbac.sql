-- =============================================================
-- V2025__tenant_and_rbac.sql  (M2 云存储·租户骨架)
-- 依据：《24 - 企业版权限落地实施文档》v2 §3.2 + 《23 - 权限判定流程图》v2
--
-- 本迁移只新增/加列，**不改任何既有列的类型与语义**，可安全回滚（只回滚代码）。
--
-- v2 关键约束（相对 v1 的差异）：
--   ★ enterprise.status 四态（加 rejected）—— 注册后需平台审核
--   ★ enterprise 删除 quota_bytes / used_bytes —— 企业没有配额概念
--   ★ users.enterprise_role —— 判"企业管理员"用它，**不用 owner_user_id**
--
-- 注意：本仓库 Flyway 已到 V2024（门户计费改造），因此本迁移从 V2025 起。
-- =============================================================

-- ① 企业（四态：pending 待审核 / active / rejected 已驳回 / disabled 已停用）
CREATE TABLE enterprise (
    id            BIGSERIAL    PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL UNIQUE,          -- 对接用，建后不可改
    name          VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending','active','rejected','disabled')),
    owner_user_id BIGINT       REFERENCES users(id),      -- 创建者/主管理员；转让时才改
    reject_reason VARCHAR(500),                           -- 驳回原因（登录 2012 时回显）
    expire_at     TIMESTAMPTZ,
    reviewed_at   TIMESTAMPTZ,
    reviewed_by   BIGINT       REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE  enterprise                 IS '企业（多租户边界）。四态，只有 active 对外服务';
COMMENT ON COLUMN enterprise.status          IS 'pending 待审核 / active / rejected 已驳回 / disabled 已停用';
COMMENT ON COLUMN enterprise.owner_user_id   IS '创建者/主管理员；仅转让时变更。判权请用 users.enterprise_role';
COMMENT ON COLUMN enterprise.reject_reason   IS '审核驳回原因，供登录提示与排障';

-- ② 部门（物化路径 /1/5/12/，含自身；"本部门+下级"一条 LIKE 即可）
CREATE TABLE dept (
    id            BIGSERIAL    PRIMARY KEY,
    enterprise_id BIGINT       NOT NULL REFERENCES enterprise(id),
    parent_id     BIGINT       REFERENCES dept(id),
    name          VARCHAR(64)  NOT NULL,
    path          VARCHAR(512) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    sort_no       INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_dept_ent_path ON dept(enterprise_id, path);
CREATE UNIQUE INDEX uk_dept_name ON dept(enterprise_id, parent_id, name) WHERE status = 'active';
COMMENT ON COLUMN dept.path IS '物化路径，形如 /1/5/12/（含自身）。数据范围 dept_subtree 靠它 LIKE';

-- ③ 角色（按企业隔离；4 个内置模板由首次访问惰性创建）
CREATE TABLE role (
    id            BIGSERIAL    PRIMARY KEY,
    enterprise_id BIGINT       NOT NULL REFERENCES enterprise(id),
    code          VARCHAR(48)  NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    builtin       BOOLEAN      NOT NULL DEFAULT FALSE,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active' CHECK (status IN ('active','disabled')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_role_code UNIQUE (enterprise_id, code)   -- 防惰性初始化并发生成两份
);

-- ④ 权限点字典（40 行，8 个分组；全局，不按企业隔离）
CREATE TABLE permission (
    code       VARCHAR(48) PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    group_code VARCHAR(32) NOT NULL,
    sort_no    INTEGER     NOT NULL DEFAULT 0,
    high_risk  BOOLEAN     NOT NULL DEFAULT FALSE
);

-- ⑤ 角色→权限点 / 用户→角色
CREATE TABLE role_permission (
    role_id         BIGINT      NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_code VARCHAR(48) NOT NULL REFERENCES permission(code),
    PRIMARY KEY (role_id, permission_code)
);
CREATE TABLE user_role (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_role_role ON user_role(role_id);

-- ⑥ 资源包（holder NOT NULL：资源永远有主 = 主管理员；资源归人不归企业）
CREATE TABLE storage_resource (
    id             BIGSERIAL   PRIMARY KEY,
    enterprise_id  BIGINT      NOT NULL REFERENCES enterprise(id),
    sku_id         VARCHAR(64) NOT NULL,
    quota_gb       INTEGER     NOT NULL CHECK (quota_gb > 0),
    holder_user_id BIGINT      NOT NULL REFERENCES users(id),
    assigned_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_res_ent_holder ON storage_resource(enterprise_id, holder_user_id);
COMMENT ON COLUMN storage_resource.holder_user_id IS '当前持有人（默认主管理员）。收回 = 改回主管理员，故不存在"收回后超额"';

-- ⑦ 既有表加列
ALTER TABLE users ADD COLUMN enterprise_id BIGINT NOT NULL DEFAULT 0;   -- 0 = 个人域
ALTER TABLE users ADD COLUMN dept_id       BIGINT;
ALTER TABLE users ADD COLUMN data_scope    VARCHAR(16) NOT NULL DEFAULT 'self'
                   CHECK (data_scope IN ('self','dept_subtree','all'));
-- ★ v2：判"企业管理员"用这个字段（不是 owner_user_id），便于将来扩多管理员
ALTER TABLE users ADD COLUMN enterprise_role SMALLINT NOT NULL DEFAULT 0
                   CHECK (enterprise_role IN (0,1));

CREATE INDEX idx_users_ent      ON users(enterprise_id, dept_id);
CREATE INDEX idx_users_ent_role ON users(enterprise_id, enterprise_role);

ALTER TABLE audit_logs ADD COLUMN enterprise_id BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_audit_ent_time ON audit_logs(enterprise_id, created_at DESC);

COMMENT ON COLUMN users.enterprise_id   IS '所属企业；0 = 个人域（与企业域隔离）';
COMMENT ON COLUMN users.enterprise_role IS '1 = 企业管理员（创建者/主管理员）；判权用它';

-- ⑧ 权限点种子（40 个，8 个分组）
INSERT INTO permission (code, name, group_code, sort_no, high_risk) VALUES
  ('file.read',            '浏览与下载',     'file',          10, FALSE),
  ('file.upload',          '上传',           'file',          11, FALSE),
  ('file.modify',          '编辑',           'file',          12, FALSE),
  ('file.delete',          '删除',           'file',          13, FALSE),
  ('recycle.read',         '查看回收站',     'file',          14, FALSE),
  ('recycle.restore',      '还原',           'file',          15, FALSE),
  ('recycle.purge',        '彻底删除',       'file',          16, TRUE),
  ('file.other.read',      '查看下级的文件', 'file',          17, FALSE),
  ('file.other.delete',    '删除下级的文件', 'file',          18, TRUE),
  ('transfer.read',        '查看传输列表',   'transfer',      20, FALSE),
  ('transfer.control',     '控制传输',       'transfer',      21, FALSE),
  ('quota.read',           '查看配额与用量', 'quota',         30, FALSE),
  ('billing.read',         '查看账单与流水', 'quota',         31, FALSE),
  ('increase.apply',       '提交增额申请',   'quota',         32, FALSE),
  ('increase.read',        '查看增额申请',   'quota',         33, FALSE),
  ('org.dept.read',        '查看部门',       'org',           40, FALSE),
  ('org.dept.manage',      '管理部门',       'org',           41, TRUE),
  ('org.member.read',      '查看成员',       'org',           42, FALSE),
  ('org.member.manage',    '管理成员',       'org',           43, TRUE),
  ('org.member.resource',  '配资源',         'org',           44, TRUE),
  ('role.read',            '查看角色',       'org',           45, FALSE),
  ('role.manage',          '管理角色',       'org',           46, TRUE),
  ('admin.user.read',      '查看用户',       'admin_user',    50, FALSE),
  ('admin.user.manage',    '管理用户',       'admin_user',    51, TRUE),
  ('admin.user.quota',     '调整配额',       'admin_user',    52, TRUE),
  ('admin.billing.read',   '查看计费配置',   'admin_billing', 60, FALSE),
  ('admin.billing.manage', '修改计费配置',   'admin_billing', 61, TRUE),
  ('admin.increase.read',  '查看增额申请',   'admin_billing', 62, FALSE),
  ('admin.increase.approve','审批增额申请',  'admin_billing', 63, TRUE),
  ('admin.ledger.read',    '查看缴费台账',   'admin_billing', 64, FALSE),
  ('admin.ledger.export',  '导出台账',       'admin_billing', 65, TRUE),
  ('admin.audit.read',     '查看审计日志',   'admin_audit',   70, FALSE),
  ('admin.audit.export',   '导出审计日志',   'admin_audit',   71, TRUE),
  ('admin.dashboard.read', '查看统计大盘',   'admin_audit',   72, FALSE),
  ('admin.dashboard.export','导出报表',      'admin_audit',   73, TRUE),
  ('admin.release.read',   '查看版本',       'release_sys',   80, FALSE),
  ('admin.release.manage', '发布版本',       'release_sys',   81, TRUE),
  ('sys.config.read',      '查看系统配置',   'release_sys',   82, FALSE),
  ('sys.config.manage',    '修改系统配置',   'release_sys',   83, TRUE),
  ('sys.secret.manage',    '密钥管理',       'release_sys',   84, TRUE)
ON CONFLICT (code) DO UPDATE
  SET name       = EXCLUDED.name,
      group_code = EXCLUDED.group_code,
      sort_no    = EXCLUDED.sort_no,
      high_risk  = EXCLUDED.high_risk;
