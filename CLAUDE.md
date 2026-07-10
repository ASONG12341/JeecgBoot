# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 默认使用 **简体中文** 回答，除非用户明确要求其他语言。

## 仓库结构

本仓库为 monorepo，包含两个可独立构建的子项目，每个子项目都有自己的详细 `CLAUDE.md`：

| 目录 | 技术栈 | 子项目 CLAUDE.md |
|------|--------|------------------|
| `jeecg-boot/` | Spring Boot 4.1.0 / Java 17 / MyBatis-Plus / Shiro-JWT | [`jeecg-boot/CLAUDE.md`](jeecg-boot/CLAUDE.md) |
| `jeecgboot-vue3/` | Vue 3 / Vite 6 / Ant Design Vue 4 / TypeScript / pnpm | [`jeecgboot-vue3/CLAUDE.md`](jeecgboot-vue3/CLAUDE.md) |

**在任一子项目中动手之前，请先阅读对应的 `CLAUDE.md`。** 模块架构、命名规范、响应包装类、命令细节、栈特定坑点等内容都已在子项目文档中说明，本文件不重复。

其他顶层文件：
- `README.md` / `README.en-US.md` / `README.ja-JP.md` / `README-AI.md` — 产品与功能文档（中文 / English / 日本語 / AIGC）
- `jeecg-boot/UPGRADE-3.9.3.md` — **必读**：记录 Spring Boot 3 → 4、Spring Framework 6 → 7、Jackson 2 → 3 的迁移与破坏性变更（如 `UriComponentsBuilder.fromHttpUrl()` 已移除、RedisMessageListenerContainer Bean 冲突、包名空间为 `jakarta.*` 而非 `javax.*`）
- `docker-compose.yml` — 全栈本地环境（MySQL 13306、Redis、pgvector、后端应用 8080、Vue3 nginx 80）
- `docker-compose-cloud.yml` — Spring Cloud 微服务变体
- `start-docker-compose.sh` / `.bat` — 本地环境一键启动脚本
- `check_jeecgenv.py` — 本地开发环境检测脚本（Python）
- `db/jeecgboot-mysql-5.7.sql`（位于 `jeecg-boot/` 下）— 基础库脚本；Flyway 增量迁移脚本位于 `jeecg-boot/.../resources/flyway/sql/mysql/`

## 仓库级通用约定

以下规则**同时适用于**后端与前端代码。

### 代码修改痕迹日志 — 强制要求

所有新增或修改的代码块必须使用 `update-begin` / `update-end` 注释包裹：

```java
//update-begin---author:作者 ---date:YYYY-MM-DD  for：【bug号/需求号】修改说明-----------
// 新增或修改的代码
//update-end---author:作者 ---date:YYYY-MM-DD  for：【bug号/需求号】修改说明-----------
```

规则：
- `author` 填写实际修改人；`date` 格式为 `YYYY-MM-DD`；`for` 填写 bug 号或需求号 + 简要说明
- 新增方法：`update-begin` 放在方法声明前一行，`update-end` 放在方法结束 `}` 后一行
- 修改已有方法内部代码：只包裹被修改的代码段，不包裹整个方法
- 用户未提供 bug 号 / 需求号时，主动询问，不得编造

该约定在两个子项目中均存在；编辑前请查看周边已有代码以匹配风格，并参考历史上使用的作者与日期命名习惯。

### 分支与提交规范

- 主分支为 `main`。近期提交包含 Spring Boot 4 升级相关工作；代码中应使用 `jakarta.*`（而非 `javax.*`）和 Jackson 3（`tools.jackson.*`，而非 `com.fasterxml.jackson.*`）
- 提交信息为简短的中文描述；除非用户明确要求，不要编造 commit message

## 常用开发流程

### 一键启动全栈本地环境（首次搭建推荐）

```bash
# 在仓库根目录执行
./start-docker-compose.sh        # Linux/macOS（Git Bash）
start-docker-compose.bat         # Windows cmd
```

会拉起 MySQL（13306）、Redis、pgvector、后端容器（8080，context-path `/jeecg-boot`）以及 Vue3 nginx（80）。MySQL 容器会自动加载 `db/jeecgboot-mysql-5.7.sql` 基础库。

### 后端开发（不使用 Docker，热重启）

```bash
cd jeecg-boot/jeecg-module-system/jeecg-system-start
mvn spring-boot:run
```

需要本地已启动 MySQL 与 Redis，地址见 `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`。激活的 profile 由 Maven 资源过滤的 `@profile.name@` 注入。

### 前端开发

```bash
cd jeecgboot-vue3
pnpm dev          # http://localhost:3100，已开启 mock，请求代理到 localhost:8080/jeecg-boot
```

完整的前端命令与架构说明请见 `jeecgboot-vue3/CLAUDE.md`。

### 构建生产产物

```bash
# 后端（在 jeecg-boot/ 下执行）— 默认跳过测试（surefire 配置）
mvn clean package

# 后端并执行测试
mvn clean package -DskipTests=false

# 后端构建并包含微服务模块
mvn clean package -P SpringCloud

# 前端（在 jeecgboot-vue3/ 下执行）
pnpm build
pnpm build:docker     # 用于 docker-compose 中的前端镜像
```

## 跨项目关注点

- **单体 ↔ Spring Cloud 切换**（后端）：`jeecg-module-system/jeecg-system-api` 提供两套并行实现 —— `jeecg-system-local-api`（方法直调，默认）与 `jeecg-system-cloud-api`（Feign 客户端）。切换通过在启动模块中替换依赖完成，**不要**修改业务代码
- **Online 低代码元数据驱动模块**（`jeecg-boot-module-online`）：配置保存在数据库表（`onl_cgform_*`）中，不在文件中。字段类型与配置 schema 详见 `jeecg-boot/online-form-schema.md`（子项目 CLAUDE.md 中有补充说明）
- **前端外部包**（`@jeecg/online`、`@jeecg/aiflow`）：CJS 包，已从 Vite `optimizeDeps` 排除，通过 `src/main.ts` 中的 `registerPackages(app)` 注册
- **Spring Boot 4 迁移说明**（`jeecg-boot/UPGRADE-3.9.3.md`）：后端工作前的**必读**内容 —— Jackson 3、Spring Framework 7 API 移除、Flyway 自动装配排除等是反复踩坑的源头

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **JeecgBoot** (34767 symbols, 75686 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/JeecgBoot/context` | Codebase overview, check index freshness |
| `gitnexus://repo/JeecgBoot/clusters` | All functional areas |
| `gitnexus://repo/JeecgBoot/processes` | All execution flows |
| `gitnexus://repo/JeecgBoot/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
