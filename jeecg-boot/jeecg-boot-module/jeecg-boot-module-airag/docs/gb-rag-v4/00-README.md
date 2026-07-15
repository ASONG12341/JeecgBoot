# GB-RAG v4 文档集（单一可信设计源）

> **创建日期**：2026-07-15
> **作者**：song（与 ZCode 协作重建）
> **适用模块**：`jeecg-boot-module-airag`
> **设计哲学**：领域无关、文档驱动、固定语义槽位

---

## 1. 这份文档集是什么

本文件夹是 **GB 国标 RAG 系统的唯一设计源头**。在此之前的所有 GB-RAG 文档（根因复盘、Phase2 实施总结、各种设计稿）已归档或废弃，**不再作为决策依据**。

凡是 GB-RAG 相关的架构决策、表结构、接口签名、旧代码处置，一律以本文件夹为准。

## 2. 阅读顺序

| 文件 | 内容 | 先读给谁 |
|------|------|---------|
| `00-README.md`（本文件） | 文档集导航 + 旧文档处置清单 + 维护规则 | 所有人 |
| `01-设计总纲.md` | 架构、五层模型、核心原则、领域无关哲学 | 决策者、所有开发者 |
| `02-L1-数据模型与表结构.md` | 六张表 + 固定语义槽位 + PG18 Flyway 迁移 | 后端、DBA |
| `03-L2-入库管线与用户确认页.md` | MinerU → 结构解析 → 用户确认 → LLM 批量抽取 → 双写 | 后端、前端 |
| `04-L3-在线检索层.md` | 规则路由意图 + LLM 槽位抽取 + 三通道 RRF + 引用上下文 | 后端 |
| `05-L4-Tool-Calling层.md` | GbCalculationTool + 引用强制注入 | 后端 |
| `06-现有代码处置与迁移方案.md` | 三套旧方案对照 + 保留/重构/废弃清单 | 重构执行者 |
| `07-分阶段实施路线.md` | Phase 划分 + Kill Switch + 回滚矩阵 + 审计埋点前置 | 项目经理、所有开发者 |

**建议第一遍按 01 → 07 顺序通读**，建立全局视图；之后按需查阅单层文档。

## 3. 与旧文档的关系

- `legacy/` 子目录：归档的历史设计文档（可追溯思路演进，但**不再是决策依据**）。
- 根因复盘文档（`GB-RAG-14根因复盘与完整落地方案.md`）：作者明确要求保留内容不动，已挪入 `legacy/`。其中"14 根因分析"仍有参考价值，但"落地技术方案"已被本集覆盖。

## 4. 文档维护规则

1. **任何架构决策变更，必须先改本文档集，再改代码。** 文档领先代码。
2. 每个文档文件顶部保留版本与日期；实质性修改更新日期并简述变更。
3. 新增设计决策不另起新文件，合并进对应分层文档（01-07）。
4. 实施计划（plan）不属于本集，写在 `docs/superpowers/plans/`，引用本文档集作为依据。
5. 代码 PR 必须在描述里引用所依据的本文档文件号（如"依据 02-L1 §3.2"）。

---

## 5. 旧文档处置清单（请 song 审阅后自行执行）

> ⚠️ **以下命令不会由 AI 自动执行。** 删除是不可逆动作，请 song 逐条确认后自己在终端运行。
> 建议执行前先 `git status` 确认工作区干净，或先 commit 当前未提交改动。

### 5.1 归档到 `legacy/`（保留内容，移入隔离子目录）

```bash
# airag 模块内的历史文档
git mv "jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/GB-RAG-14根因复盘与完整落地方案.md" \
       "jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/gb-rag-v4/legacy/"

# docs/superpowers 下的旧设计稿（与 v4 方向一致但已被 v4 取代）
git mv "docs/superpowers/specs/2026-07-14-gb-standard-rag-engine-design.md" \
       "jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/gb-rag-v4/legacy/"
git mv "docs/superpowers/specs/2026-07-14-gb-rag-retrieval-frontend-design.md" \
       "jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/gb-rag-v4/legacy/"
```

### 5.2 直接删除（冗余 / 失真 / 已知坏文件）

> 这些文件是"越改越乱"的主要污染源。删除理由见下表，每条都已核实。

| 待删除文件 | 删除理由 |
|-----------|---------|
| `GB-RAG-Phase2实施总结.md` | 声称"全部完成"，但深度检查报告指出 5 个严重问题 0 个修复。**内容失真**，误导性强 |
| `GB-RAG-Phase2-问题修复总结.md` | 配套文档，"已修复"结论被后续深度检查推翻 |
| `GB-RAG-Phase2-严重问题修复报告.md` | 同上，一次性快照，价值已沉淀进 `06-现有代码处置` |
| `GB-RAG-Phase2-深度检查报告.md` | 一次性检查快照，问题清单已沉淀进 `06` |
| `GB-RAG-Phase2实施计划.md` | 基于错误总结的计划，无价值 |
| `RAG-新会话提示词-v3.md` | AI 生成的提示词，非设计文档，绑定已废弃的 v3 路线 |
| `gb_standard_indexes.sql` | **字段名 bug**（`parent_id`/`level`/`content`/`term_name` 对不上实体），已知坏文件 |
| `pg18-migration.sql` | 明确"不接入 Flyway"，且 `gb_param` vs `gb_parameter` 命名冲突。已被 `02-L1` 重写 |

```bash
cd "jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/"

# 删除失真的 Phase2 文档
git rm "GB-RAG-Phase2实施总结.md" \
       "GB-RAG-Phase2-问题修复总结.md" \
       "GB-RAG-Phase2-严重问题修复报告.md" \
       "GB-RAG-Phase2-深度检查报告.md" \
       "GB-RAG-Phase2实施计划.md" \
       "RAG-新会话提示词-v3.md"

# 删除已知坏文件 / 已被 02-L1 重写的文件
git rm "gb_standard_indexes.sql" \
       "pg18-migration.sql"

# 删除基于已废弃设计的旧 plan
cd ../../../../..
git rm "docs/superpowers/plans/2026-07-14-gb-standard-engine-phase1.md" \
       "docs/superpowers/plans/2026-07-14-gb-rag-retrieval-frontend-p1.md"
```

### 5.3 保留（不在本次重构范围，仍有效）

| 文件 | 保留理由 |
|------|---------|
| `AI-MODULE-PANORAMA.md` | airag 全模块概览，与 GB 重构正交 |
| `官方数据.md` | 三份国标的原始解析数据，是事实材料 |
| `gb_standard_indexes_correct.sql` | 字段已对齐实体的正确索引脚本，`02-L1` 会引用 |
| `cankao/`、`ai-module/` | 参考资料 |

### 5.4 处置后的预期目录结构

```
jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/docs/
├── AI-MODULE-PANORAMA.md          (保留)
├── 官方数据.md                      (保留)
├── gb_standard_indexes_correct.sql (保留)
├── cankao/                          (保留)
├── ai-module/                       (保留)
└── gb-rag-v4/                      ← 唯一设计源
    ├── 00-README.md
    ├── 01-设计总纲.md
    ├── 02-L1-数据模型与表结构.md
    ├── 03-L2-入库管线与用户确认页.md
    ├── 04-L3-在线检索层.md
    ├── 05-L4-Tool-Calling层.md
    ├── 06-现有代码处置与迁移方案.md
    ├── 07-分阶段实施路线.md
    └── legacy/                      ← 归档的历史文档（只读参考）
        ├── GB-RAG-14根因复盘与完整落地方案.md
        ├── 2026-07-14-gb-standard-rag-engine-design.md
        └── 2026-07-14-gb-rag-retrieval-frontend-design.md
```
