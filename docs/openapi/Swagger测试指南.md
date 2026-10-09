# Swagger 接口文档 · 使用与测试指南

> 面向：需要联调/测试门户接入（以及云存储其它接口）的同学
> 导出时间：2026-10-09　｜　OpenAPI 版本：3.0.1　｜　路径总数：49（其中门户相关 6 个）

---

## 一、三个入口

应用启动后（默认 `http://localhost:3001`，`context-path=/api`）：

| 用途 | 地址 |
|---|---|
| **交互式文档（推荐用它测试）** | `http://<主机>:3001/api/swagger-ui/index.html` |
| OpenAPI JSON（给工具导入） | `http://<主机>:3001/api/v3/api-docs` |
| OpenAPI YAML | `http://<主机>:3001/api/v3/api-docs.yaml` |
| 本仓库内的导出快照 | [`cloud-server-openapi.json`](./cloud-server-openapi.json) ／ [`cloud-server-openapi.yaml`](./cloud-server-openapi.yaml) |

> ⚠️ **注意 `/api` 前缀**：项目的 `server.servlet.context-path=/api`，所以是 `/api/swagger-ui/index.html`，不是 `/swagger-ui/index.html`。
> 想不写 `/api`，可加启动参数 `--springdoc.swagger-ui.path=/swagger-ui.html` 之类的自定义配置，但没必要。

---

## 二、测试前必做：点右上角 **Authorize**

门户相关接口分两组、两套鉴权，Swagger UI 的 Authorize 弹窗里各填一次即可（填完会自动带到后续请求）。

### 1. `internalKey` —— 给 `/api/internal/**` 用

| 填什么 | 平台侧下发的内部密钥（后端配置项 `app.portal.internal-key`） |
|---|---|
| 本地开发示例 | `verify-key-123`（启动时 `PORTAL_INTERNAL_KEY` 环境变量的值） |

> **未配置密钥时后端 fail-closed**：任何 `/internal/**` 调用都返回 `HTTP 401 {"code":40001}`。
> 也就是说，本地起服务如果忘了设 `PORTAL_INTERNAL_KEY`，这个接口一定调不通，这是**预期行为**。

### 2. `bearerAuth` —— 给 `/api/admin/portal/**` 用

先调 `POST /api/auth/login`（默认账号 `admin / Admin@123`），再：

1. 从响应里取 **`data.accessToken`**（注意：**不是** `data.token`，响应里没有 `token` 字段）；
2. 回到 Authorize，粘进 `bearerAuth`。

> 只填 **token 本身**，**不要**带 `Bearer ` 前缀 —— Swagger UI 会自动加。

---

## 三、推荐测试顺序（照做即可覆盖全部关键行为）

| # | 操作 | 预期 |
|---|---|---|
| 1 | 不填 `internalKey`，直接 `GET /api/internal/products` | `HTTP 401` + `{"code":40001}` —— 验证 fail-closed |
| 2 | 填好 `internalKey`，再调 `GET /api/internal/products` | `HTTP 200`，`data.products` 4 个种子 SKU，`price_cents` 是整数分 |
| 3 | `POST /api/internal/provision`，用示例体（`order_item_id=50001`） | `HTTP 200` + `{"code":0,"data":{"instance_id":"st-000001","expire_at":"...","quantity":1}}` |
| 4 | **原样再调一次**（同一个 `order_item_id`） | 返回**完全相同的 `instance_id`**，且数据库里该 `order_item_id` 只有一行 —— 验证幂等 |
| 5 | 把 `sku_id` 改成 `not-exist-sku` 再调 | `HTTP 200` + `{"code":4003,...}` —— 验证「HTTP 恒为 200」与码值语义 |
| 6 | 取 `accessToken` 填 `bearerAuth`，调 `POST /api/admin/portal/reconciliation/report` | `HTTP 200`，`accepted:true` |

追加两个「验证机制」的用例（可选）：

- **`quantity` 越界**：传 `quantity: 2147483647` → `{"code":5001,"message":"quantity 超出允许范围 1~1000..."}`，
  且**不会**留下成功记录。这是**曾经的缺陷**（long 溢出导致「假成功」），已修复，值得复验。
- **「先落库再扣费」**：把 `PORTAL_BASE_URL` 指向一个不可达端口，再调 `POST /api/admin/portal/bills/charge`。
  响应是失败，但 `b_bill` 表里**一定有一条 `status=0` 的记录** —— 钱没扣成、账记得住。

---

## 四、四个必须知道的约定（否则一定踩）

| # | 约定 | 说明 |
|---|---|---|
| 1 | **金额单位是「分」** | `price_cents` / `amount_cents` 全链路都是分，不是元。`50000` = 500 元 |
| 2 | **`/internal/provision` 的 HTTP 状态码恒为 200** | 成败看 body 的 `code`（`0`/`4003`/`4004`/`5001`）。这是门户契约要求的，POST 默认的 201 会让门户判错 |
| 3 | **两套命名风格** | `/internal/**` 用 **snake_case**（`order_item_id`，契约强制）；`/admin/portal/**` 用 **camelCase**（`portalUserId`，无契约约束，沿用 Java 风格）。**不要混用**，否则字段为 null |
| 4 | **`order_item_id` 是幂等键** | 同一个值重复调用只开通一次；想测「新开通」必须换一个新的 `order_item_id` |

---

## 五、两个容易误判的现象

1. **`POST /api/admin/portal/bills/charge` 有时要等 30 多秒**
   门户持续系统失败时，后端会按 `1s/5s/30s` **用同一个 bill_no 退避重试**（契约要求），最坏约 36 秒才返回。
   这是正确行为，不是卡死。本地联调可用环境变量缩短：

   ```
   PORTAL_DEDUCT_RETRY_DELAYS_MS=100,100,100
   ```

2. **对账上报返回 `billCount: 0` 不代表失败**
   上报只包含发生过真实资金流水的账单（`status=1` 已入账 / `status=2` 已退款）。
   `status=0` 的待扣账单会被排除，并计入返回的 `pendingCount`（可用来发现长期卡住未扣的账单）。

---

## 六、导入到 Postman / Apifox / Swagger Editor

用 [`cloud-server-openapi.json`](./cloud-server-openapi.json)（或 `.yaml`）导入即可，两套安全方案都会带过去：

- Postman：`Import` → 选文件 → 在 Collection 的 `Authorization`/`Headers` 里配置
  `X-Internal-Key` 与 `Authorization: Bearer <token>`。
- Apifox / Swagger Editor / YApi：直接导入 OpenAPI 3.0 文件。

> ⚠️ **导出的 spec 里 `servers[0].url` 是导出时抓到的地址**（本次为 `http://127.0.0.1:3001/api`）。
> 导入 Postman 后请把 baseUrl 改成你实际要连的环境，或建一个 `baseUrl` 环境变量。

---

## 七、如何重新导出 spec（代码改动后同步）

`docs/openapi/` 下的快照是**某个时间点的导出结果**，接口有变动时需要重新导出：

```powershell
$base = "http://127.0.0.1:3001/api"
# 用 curl -o 直接落盘；不要用 PowerShell 变量接收再写文件
# （curl 的多行输出被 PS 捕获会变成字符串数组，写盘时会被按空格拼接而损坏 YAML）
curl.exe -s -o docs/openapi/cloud-server-openapi.json "$base/v3/api-docs"
curl.exe -s -o docs/openapi/cloud-server-openapi.yaml "$base/v3/api-docs.yaml"
```

> 上面那条注释是**踩过的坑**：`$yaml = curl.exe ...; [IO.File]::WriteAllText($p, $yaml)` 会把 2700 多行 YAML
> 拼成一整行，文件看着有 70 KB 其实完全不可用。用 `-o` 让 curl 自己写字节最稳。

---

## 八、Swagger UI 里能看到什么

门户相关接口已补全注解（见 `cloud-portal` 的 `OpenApiConfig` 与三个 Controller）：

- **两个安全方案**：`internalKey`（请求头 `X-Internal-Key`）、`bearerAuth`（Bearer JWT），右上角 Authorize 可用；
- **分组标签**：`门户内网接口`、`门户运维接口`，各自带说明；
- **每个接口的 summary 与详细描述**：含码值语义表、幂等铁律、入参约束；
- **请求/响应示例**：可直接「Try it out」；
- **DTO 字段级中文说明与示例**：`@Schema` 覆盖 `ProvisionRequest` / `ProductItem` / `ProvisionVO` /
  `AdminChargeRequest` / `AdminRefundRequest` 等，鼠标悬停在字段上即可看到。

> 顺带修了一个既有的小配置缺口：`SecurityConfig` 原先只放行 `/v3/api-docs/**`，
> 匹配不到带点号的 `/v3/api-docs.yaml`，导致 YAML 导出返回 `401`。现已把该端点一并放行。
