
# JeecgBoot 3.9.3 开发规范指南
为保证代码质量、可维护性、安全性与可扩展性，请在开发过程中严格遵循以下规范。

---

## 一、基础项目信息
### 1. 用户工作目录
当前项目根工作目录为：`D:\git_xiangmu\JeecgBoot\jeecg-boot`，所有开发操作均在此目录下进行。
### 2. 代码作者
- 项目主要开发维护方：北京国炬信息技术有限公司
- 当前项目操作用户标识：ThinkPad
### 3. 构建工具
使用 **Maven** 作为项目构建工具，推荐版本3.8+，IDE需配置与项目一致的Maven环境。
- 主启动类：`org.jeecg.JeecgSystemApplication`（位于`jeecg-module-system/jeecg-system-start`模块）
- 多环境配置支持：dev（开发，默认激活）、test（测试）、docker（容器打包）、prod（生产）、SpringCloud（微服务运行环境），禁止硬编码环境相关配置
- 打包规则：默认跳过单元测试，编译编码统一为UTF-8，字体、图片、音视频等二进制资源文件不参与Maven资源过滤，避免编译破坏
### 4. 项目目录结构
```markdown
jeecg-boot/
├── .claude/                    # 项目自定义配置目录
├── db/                         # 数据库脚本目录
│   └── 其他数据库脚本
├── jeecg-boot-base-core/       # 核心基础模块（通用能力、底层封装）
│   └── src/
│       ├── main/
│       │   ├── java/org/jeecg/
│       │   │   ├── common/     # 通用组件
│       │   │   │   ├── api/    # 通用接口定义
│       │   │   │   ├── aspect/ # 切面（权限、脱敏、日志等）
│       │   │   │   ├── config/ # 全局配置类
│       │   │   │   ├── constant/# 常量、枚举
│       │   │   │   ├── desensitization/ # 数据脱敏能力
│       │   │   │   ├── exception/ # 全局异常处理
│       │   │   │   ├── handler/ # 统一处理器
│       │   │   │   ├── util/   # 通用工具类
│       │   │   │   └── modules/base/ # 基础模块
│       │   │   │       ├── mapper/ # 数据访问层
│       │   │   │       │   └── xml/ # MyBatis XML映射文件
│       │   │   │       └── service/ # 服务层
│       │   │   │           └── impl/ # 服务实现
│       │   │   └── config/     # 核心配置（数据源、Shiro、MyBatis等）
│       │   └── resources/      # 核心资源文件
│       └── test/               # 核心测试代码
├── jeecg-boot-module/         # 业务模块集合
│   ├── jeecg-boot-module-airag/ # AI RAG 业务模块
│   │   ├── doc/               # 模块说明文档
│   │   ├── docs/              # 其他文档
│   │   └── src/
│   │       ├── main/java/org/jeecg/modules/airag/ # 模块业务代码
│   │       │   ├── api/       # 模块接口定义
│   │       │   ├── app/       # 应用层入口
│   │       │   ├── demo/      # 示例代码
│   │       │   ├── llm/       # 大模型接入、配置相关
│   │       │   ├── ocr/       # OCR识别相关
│   │       │   ├── prompts/   # 提示词管理
│   │       │   ├── video/     # 视频处理相关
│   │       │   ├── voice/     # 语音处理相关
│   │       │   └── wordtpl/   # Word模板引擎相关
│   │       └── test/          # 模块测试代码
│   └── jeecg-module-demo/     # 示例业务模块
│       └── src/main/
│           ├── java/org/jeecg/modules/demo/ # 示例业务代码
│           └── resources/     # 静态资源、大屏模板
├── jeecg-module-system/       # 系统管理模块集合
│   ├── jeecg-system-api/      # 系统API模块（对外接口定义）
│   │   ├── jeecg-system-cloud-api/ # 微服务版API（Feign接口）
│   │   └── jeecg-system-local-api/ # 单体版API（本地调用接口）
│   ├── jeecg-system-biz/      # 系统业务实现模块
│   └── jeecg-system-start/    # 系统启动模块（主入口、Flyway脚本）
└── jeecg-server-cloud/        # 微服务模块
    └── jeecg-cloud-gateway/   # 微服务网关
```

---

## 二、技术栈与SDK版本
### 1. 核心基础版本
| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17.0.19 | 项目最低支持JDK 17，兼容17/21/24/25版本 |
| Spring Boot | 4.1.0 | 父POM指定版本 |
| Spring Cloud | 2025.1.2 | 微服务组件版本 |
| Spring Cloud Alibaba | 2025.1.0.0 | 阿里云微服务组件版本 |
| JeecgBoot 核心版本 | 3.9.3 | 项目基础框架版本 |

### 2. 核心依赖版本
| 依赖组件 | 版本 | 用途说明 |
|----------|------|----------|
| MyBatis-Plus | 3.5.16 | 持久层框架 |
| Druid | 1.2.28 | 数据库连接池 |
| Shiro | 3.0.0 | 权限安全框架 |
| Lombok | 对应Spring Boot 4.1.0版本 | 实体类简化工具 |
| Fastjson | 2.0.58 | JSON处理工具 |
| Hutool | 5.8.25 | 国产工具类库 |
| LangChain4j | 1.12.2 | AI大模型开发框架 |
| LiteFlow | 2.15.0 | AI流程编排框架 |
| Flyway | 7.15.0 | 数据库自动迁移工具 |
| 积木报表 | 2.5.0 | 报表引擎 |
| AutoPoi | 2.0.4 | Excel导入导出工具 |
| 数据库驱动 | 按需引入 | 支持MySQL、PostgreSQL、Oracle、SQL Server、达梦8、人大金仓 |

---

## 三、分层架构规范
JeecgBoot项目遵循标准分层架构，各层职责与约束如下：
| 层级 | 职责说明 | 开发约束与注意事项 |
|------|----------|--------------------|
| **Controller** | 处理HTTP请求与响应，定义API接口 | 不得直接访问数据库，必须通过Service层调用；接口返回值统一封装为Jeecg响应格式 |
| **Service** | 实现业务逻辑、事务管理与数据校验 | 必须通过Repository/Mapper层访问数据库；优先返回DTO/VO，禁止直接返回Entity |
| **Repository/Mapper** | 数据库访问与持久化操作 | 继承Jeecg封装的`BaseMapper`或`JpaRepository`；复杂查询使用`@EntityGraph`避免N+1问题；禁止手动拼接SQL，使用MyBatis-Plus或MiniDao的API |
| **Entity/DO** | 映射数据库表结构 | 不得直接返回给前端，需转换为DTO/VO；实体类统一使用Lombok注解简化代码 |

### 接口与实现分离规范
1. 所有Service接口需定义在`service`包下，实现类放在同包下的`impl`子包中
2. 模块间调用需通过API模块的接口定义，禁止直接依赖业务实现模块：
   - 单体架构调用`jeecg-system-local-api`模块接口
   - 微服务架构调用`jeecg-system-cloud-api`模块接口，通过Feign实现远程调用

---

## 四、安全与性能规范
### 1. 输入校验
- 使用Jakarta Validation注解（`jakarta.validation.constraints.*`）配合`@Valid`实现参数校验，禁止手动拼接SQL防止SQL注入
- Jeecg自带的SQL注入防护、XSS防护等安全配置禁止私自关闭

### 2. 权限与数据安全
- 基于Shiro实现权限校验，接口需添加对应的权限注解，禁止绕过权限校验直接访问接口
- 敏感数据（手机号、身份证、密码、密钥等）需进行脱敏处理，使用Jeecg提供的脱敏工具类
- 对外接口需添加签名校验，防止篡改与重放攻击
- AI模块的大模型密钥、提示词等敏感配置需加密存储，禁止硬编码到代码中；用户输入的提示词需做过滤，防止Prompt注入攻击

### 3. 事务管理
- `@Transactional`注解仅用于Service层方法，禁止在Controller、Mapper层使用
- 事务传播行为默认使用`REQUIRED`，特殊场景需明确指定传播行为
- 避免在循环中频繁提交事务，影响性能
- 事务方法中禁止抛出非RuntimeException，若需回滚需手动指定`rollbackFor`

### 4. 性能优化
- 数据库查询优先使用索引，禁止全表扫描
- 大流量接口需添加缓存（Redis），减少数据库压力
- 文件上传、AI推理等耗时操作需异步处理，避免阻塞主线程

---

## 五、代码风格规范
### 1. 命名规范
| 类型 | 命名方式 | 示例 |
|------|----------|------|
| 类名 | UpperCamelCase | `UserServiceImpl`、`AiRagController` |
| 方法/变量 | lowerCamelCase | `saveUser()`、`embeddingStore` |
| 常量 | UPPER_SNAKE_CASE | `MAX_LOGIN_ATTEMPTS`、`AI_RAG_TIMEOUT` |
| 包名 | 全小写，多单词用.分隔 | `org.jeecg.modules.airag.llm` |

### 2. 类型后缀规范（阿里巴巴风格）
| 后缀 | 用途说明 | 示例 |
|------|----------|------|
| DTO | 数据传输对象（层间传输） | `UserDTO`、`LlmConfigDTO` |
| DO | 数据库实体对象（映射表结构） | `UserDO`、`EmbeddingDO` |
| BO | 业务逻辑封装对象（内部业务流转） | `UserBO`、`RagQueryBO` |
| VO | 视图展示对象（返回前端） | `UserVO`、`RagResultVO` |
| Query | 查询参数封装对象 | `UserQuery`、`LlmModelQuery` |

### 3. 注释规范
- 所有类、方法、字段必须添加**中文Javadoc**注释，说明用途、参数、返回值
- 复杂业务逻辑需添加行内注释，说明实现思路
- 禁止使用无意义的注释（如`// 修改这里`、`// TODO 待完成`需说明具体待完成内容）

### 4. 实体类规范
- 统一使用Lombok注解`@Data`、`@NoArgsConstructor`、`@AllArgsConstructor`简化代码，禁止手动编写getter/setter/构造方法
- 数据库实体类需添加`@TableName`、`@TableId`等MyBatis-Plus注解，禁止使用XML映射基础CRUD
- 实体类统一放在对应模块的`entity`包下

---

## 六、专项模块规范
### 1. AI模块规范
1. 大模型接入：统一通过LangChain4j框架实现，模型配置需抽离到配置类，禁止硬编码模型地址、密钥
2. 流程编排：复杂AI流程使用LiteFlow框架实现，脚本文件统一放在对应模块的`script`目录下
3. RAG模块：向量存储配置统一在`application.yml`的`jeecg.ai-rag`节点下配置，禁止硬编码向量库地址
4. 文档解析：使用Apache Tika或LangChain4j的文档解析器，支持PDF、Word、Excel、HTML等格式
5. 提示词管理：提示词模板统一放在`prompts`模块下管理，禁止硬编码提示词

### 2. 数据库与持久层规范
1. 支持多数据源配置，动态数据源通过`@DS`注解指定数据源
2. 数据库迁移使用Flyway工具，SQL脚本统一放在`jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql`目录下
3. 支持MySQL、PostgreSQL、Oracle、SQL Server、达梦8、人大金仓等多数据库，SQL编写需兼容主流数据库，避免使用数据库专属语法
4. 复杂查询优先使用MyBatis-Plus的`LambdaQueryWrapper`，若需自定义SQL需在XML文件中编写，禁止在Java代码中拼接SQL

### 3. 微服务规范
1. Feign调用需配置超时时间、降级策略，禁止无降级的远程调用
2. 服务配置统一在Nacos配置中心管理，禁止在本地硬编码配置
3. 微服务接口需定义在对应的API模块中，实现类放在业务模块的`impl`包下

---

## 七、日志与编码原则
### 1. 日志规范
- 统一使用Slf4j注解`@Slf4j`，禁止使用`System.out.println`输出日志
- 日志级别规范：错误日志必须记录异常堆栈，调试日志仅在开发环境开启，生产环境关闭debug日志
- 敏感信息（密码、密钥、身份证号等）禁止输出到日志

### 2. 编码原则
| 原则 | 说明 |
|------|------|
| SOLID | 高内聚、低耦合，增强可维护性与可扩展性 |
| DRY | 避免重复代码，提高复用性 |
| KISS | 保持代码简洁易懂 |
| YAGNI | 不实现当前不需要的功能 |
| OWASP | 防范常见安全漏洞，如SQL注入、XSS、CSRF等 |
