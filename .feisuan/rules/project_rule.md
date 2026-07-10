
# 开发规范指南
为保证JeecgBoot项目代码质量、可维护性、安全性与可扩展性，请在开发过程中严格遵循以下规范。

## 一、项目基本信息
- **工作目录**：`D:\git_xiangmu\JeecgBoot`
- **操作系统**：Windows 10
- **开发环境**：JDK 17.0.19 + Maven
- **项目框架**：JeecgBoot 3.x（基于Spring Boot 3.x）
- **代码作者**：ThinkPad
- **规范生效时间**：2026-07-09

## 二、技术栈要求
- **主框架**：JeecgBoot 3.x（基于Spring Boot 3.x）
- **语言版本**：Java 17
- **核心依赖**：
  - `spring-boot-starter-web`、`spring-boot-starter-data-jpa`（兼容依赖）
  - `mybatis-plus-boot-starter`（核心ORM框架）
  - `lombok`（实体类简化工具）
  - `shiro-spring-boot-starter`/`sa-token-spring-boot-starter`（权限框架）
  - `quartz`/`xxl-job-core`（定时任务）
  - `jimureport-boot-starter`（报表工具）
  - `jakarta.validation-api`（参数校验）
  - 可选依赖：`spring-boot-starter-data-elasticsearch`（全文检索）、`aliyun-oss`/`minio`（对象存储）

## 三、项目目录结构
```
JeecgBoot/
├── .claude/                  # Claude配置目录
│   └── skills/               # Claude技能配置
│       └── gitnexus/         # GitNexus相关技能（代码解析、重构等）
├── .github/                  # GitHub配置目录
│   └── ISSUE_TEMPLATE/       # Issue提交模板
├── .gitnexus/                # GitNexus缓存目录
│   ├── parse-cache/
│   └── parsedfile-cache/
├── jeecg-boot/               # JeecgBoot核心基础模块（禁止随意修改核心代码）
│   ├── jeecg-boot-base-core/ # 核心基础包（公共组件、工具、配置、安全组件等）
│   │   ├── src/main/java/org/jeecg/common/  # 公共组件
│   │   │   ├── api/          # 通用接口定义
│   │   │   ├── aspect/       # 切面（日志、脱敏、权限等）
│   │   │   ├── constant/     # 常量定义
│   │   │   ├── desensitization/ # 数据脱敏组件
│   │   │   ├── es/           # Elasticsearch通用工具
│   │   │   ├── exception/    # 全局异常处理
│   │   │   ├── handler/      # 通用处理器
│   │   │   ├── system/       # 系统基础功能
│   │   │   │   ├── base/     # 基础分层模板（Controller/Entity/Service/Impl）
│   │   │   │   ├── enhance/  # 增强工具
│   │   │   │   ├── query/    # 通用查询工具
│   │   │   │   └── util/     # 系统工具类
│   │   │   ├── util/         # 通用工具类
│   │   │   │   ├── dynamic/db/ # 动态数据源工具
│   │   │   │   ├── encryption/ # 加密工具
│   │   │   │   ├── filter/   # 参数过滤工具
│   │   │   │   ├── oss/      # 对象存储工具
│   │   │   │   ├── security/ # 安全工具
│   │   │   │   ├── sqlparse/ # SQL解析工具（防注入核心）
│   │   │   │   └── superSearch/ # 超级搜索工具
│   │   │   └── config/       # 核心配置类
│   │   │       ├── filter/   # 过滤器配置
│   │   │       ├── firewall/ # SQL注入防火墙配置
│   │   │       ├── mybatis/  # MyBatis-Plus配置
│   │   │       ├── shiro/     # Shiro权限配置
│   │   │       ├── sign/      # 接口签名校验配置
│   │   │       └── tencent/   # 腾讯云服务配置
│   │   ├── src/main/resources/ # 核心资源配置
│   │   │   ├── config/       # 配置文件
│   │   │   ├── META-INF/spring/ # Spring自动装配配置
│   │   │   ├── static/       # 静态资源
│   │   │   └── templates/email/ # 邮件模板
│   │   └── src/test/          # 核心模块测试
│   ├── jeecg-boot-module/    # 业务模块集合（新增业务模块统一放在该目录下）
│   │   ├── jeecg-boot-module-airag/  # AI RAG业务模块
│   │   │   ├── llm/          # 大模型调用模块
│   │   │   ├── ocr/          # OCR识别模块
│   │   │   ├── voice/        # 语音处理模块
│   │   │   ├── video/        # 视频处理模块
│   │   │   ├── prompts/      # 提示词管理模块
│   │   │   ├── wordtpl/      # 文档模板模块
│   │   │   └── app/          # AI应用模块
│   │   └── jeecg-module-demo/ # 示例业务模块（功能参考用）
│   │       ├── cloud/        # 微服务相关示例
│   │       ├── mock/         # Mock数据示例
│   │       ├── online/       # 在线表单示例
│   │       ├── shop/         # 商城业务示例
│   │       ├── test/         # 功能测试示例
│   │       └── xxljob/       # XXL-Job分布式任务示例
│   └── jeecg-module-system/  # 系统核心业务模块
│       ├── jeecg-system-api/ # 系统接口定义模块（接口与实现分离）
│       │   ├── jeecg-system-cloud-api/ # 云服务接口定义
│       │   └── jeecg-system-local-api/ # 本地服务接口定义
│       └── jeecg-system-biz/ # 系统业务实现模块
│           ├── config/       # 业务配置类
│           ├── modules/      # 业务功能模块
│           │   ├── airag/    # AI相关业务
│           │   ├── aop/      # 业务切面
│           │   ├── api/      # 接口管理业务
│           │   ├── cas/      # CAS认证业务
│           │   ├── message/  # 消息通知业务
│           │   ├── monitor/  # 系统监控业务
│           │   ├── ngalain/  # 内网穿透业务
│           │   ├── openapi/  # 开放接口业务
│           │   ├── oss/      # 对象存储业务
│           │   ├── quartz/   # 定时任务业务
│           │   └── system/   # 系统管理业务（用户、角色、权限、部门等）
│           └── resources/jeecg/ # Jeecg资源（代码模板、静态资源、邮件模板等）
└── pom.xml                   # 父Maven工程配置（统一管理所有子模块依赖版本）
```

## 四、分层架构规范
JeecgBoot采用标准分层架构，各层级职责明确，禁止跨层调用：
| 层级        | 职责说明                         | 开发约束与注意事项                                               |
|-------------|----------------------------------|----------------------------------------------------------------|
| **Controller** | 处理 HTTP 请求与响应，定义 API 接口 | 不得直接访问数据库，必须通过 Service 层调用；需添加权限注解控制接口访问；返回统一`Result<T>`结果集，禁止直接返回实体/集合 |
| **Service**    | 实现业务逻辑、事务管理与数据校验   | 必须通过 Mapper 层访问数据库；返回 DTO/VO 而非 Entity；接口与实现分离，实现类放在同包下的`impl`子包中；`@Transactional`注解仅用于Service层方法 |
| **Mapper**    | 数据库访问与持久化操作             | 继承 MyBatis-Plus `BaseMapper`；复杂查询使用XML映射文件；禁止使用`${}`拼接SQL，必须使用`#{}`传参防止SQL注入 |
| **Entity**     | 映射数据库表结构                   | 不得直接返回给前端（需转换为 DTO/VO）；需添加`@TableName`注解关联表名、`@TableId`注解标识主键；字典类型字段添加`@Dict`注解；包名统一为`entity` |

### 接口与实现分离规范
- 所有业务接口需放在对应模块的`service`包下，实现类放在同包下的`impl`子包中，命名规则为`接口名+Impl`（如`UserServiceImpl`）。
- 跨模块调用的接口统一放在`jeecg-module-system/jeecg-system-api`下的对应模块中，禁止直接依赖其他模块的实现类。

## 五、安全与性能规范
### 输入校验规范
- 使用`@Validated`与Jakarta Validation校验注解（如`@NotBlank`、`@Size`、`@Pattern`等）进行参数校验，校验失败统一抛出`JeecgException`。
- 禁止手动拼接SQL字符串，Jeecg自带SQL注入防火墙，但仍需遵守MyBatis-Plus传参规范，复杂查询优先使用XML+`#{}`实现。
- 前端输入需做XSS转义，Jeecg自带XSS防护过滤器，禁止关闭该配置。

### 权限与安全规范
- Controller方法需添加`@Permission`注解控制接口权限，未授权请求直接拦截。
- 数据权限通过Jeecg数据权限注解控制，避免越权访问其他部门/用户的数据。
- 敏感字段（手机号、身份证号、银行卡号、密码等）需添加`@Sensitive`注解，使用Jeecg脱敏工具自动处理，禁止明文存储/返回敏感数据。
- 对外暴露的接口需添加`@Sign`注解，启用Jeecg签名校验机制，防止接口篡改和重放攻击。
- 密码等敏感信息需使用Jeecg提供的加密工具加密存储，禁止明文存储。

### 事务与性能规范
- `@Transactional`注解仅用于Service层方法，避免在循环中频繁提交事务；分布式事务需使用Seata实现，禁止使用本地事务处理跨库操作。
- 禁止在循环中执行数据库查询，优先使用批量查询/缓存优化，避免N+1查询问题。
- 大文件上传/下载需使用分片上传、断点续传机制，避免占用过多服务器资源。

## 六、代码风格规范
### 命名规范
| 类型       | 命名方式             | 示例                  |
|------------|----------------------|-----------------------|
| 类名       | UpperCamelCase       | `UserServiceImpl`、`AiRagController` |
| 方法/变量  | lowerCamelCase       | `saveUser()`、`queryList` |
| 常量       | UPPER_SNAKE_CASE     | `MAX_LOGIN_ATTEMPTS`、`DEFAULT_PAGE_SIZE` |
| 包名       | 全小写，多单词用.分隔 | `org.jeecg.modules.airag.llm` |

### 注释规范
- 所有类、方法、字段需添加**Javadoc**注释，使用中文（项目第一语言）说明用途、参数、返回值、异常。
- 类注释需说明模块归属、核心用途、作者信息；方法注释需说明业务逻辑、参数说明、返回值说明、异常场景。
- 复杂业务逻辑需添加行内注释，说明实现思路，禁止注释无意义代码，需直接删除。

### 实体类规范
- 使用Lombok注解替代手动编写getter/setter/构造方法：`@Data`、`@NoArgsConstructor`、`@AllArgsConstructor`，需要Builder模式时添加`@Builder`。
- 实体类需添加`@ApiModelProperty`注解，用于Swagger接口文档生成。
- 字典类型字段统一以`dictCode`结尾，如`sexDictCode`，添加`@Dict`注解自动关联字典值。

### 类型后缀规范
| 后缀 | 用途说明                     | 示例         |
|------|------------------------------|--------------|
| Entity | 数据库实体对象               | `UserEntity`、`AiRagDocEntity` |
| DTO  | 数据传输对象（接口入参/出参） | `UserDTO`、`AiChatDTO` |
| VO   | 视图展示对象（前端返回数据） | `UserVO`、`AiChatVO` |
| Query| 查询参数封装对象             | `UserQuery`、`AiDocQuery` |
| BO   | 业务逻辑封装对象             | `UserBO`、`AiRagBO` |

## 七、Jeecg特性专项规范
### 代码生成器规范
- 常规CRUD功能优先使用Jeecg在线代码生成器生成代码，减少重复开发，符合DRY原则。
- 生成器生成的代码如需修改，需添加`// custom start`/`// custom end`标记，避免被重新生成覆盖。
- 代码模板统一使用`jeecg-module-system/resources/jeecg/code-template`下的官方模板，禁止私自修改全局模板。

### 字典与脱敏规范
- 字典值禁止硬编码，需在系统管理-字典管理模块下配置，通过`@Dict`注解自动翻译。
- 敏感数据必须使用Jeecg脱敏组件处理，禁止手动拼接脱敏逻辑。

### 在线表单/低代码规范
- 在线表单开发需遵循Jeecg低代码规范，表单字段与数据库字段一一对应，禁止随意修改表单结构。
- 自定义前端组件需放在对应模块的`vue`/`vue3`目录下，遵循Jeecg前端开发规范，禁止修改核心组件代码。

### AI模块开发规范
- AI相关功能需封装通用接口，支持多模型切换，避免硬编码特定大模型的调用逻辑。
- 大模型调用需添加熔断、降级机制，避免服务雪崩；AI生成内容需添加安全校验，防止违规内容输出。
- AI相关数据需单独存储，禁止与业务核心数据混存。

### 定时任务规范
- 定时任务统一使用Quartz或XXL-Job实现，禁止使用`Timer`等原生工具。
- 任务类需添加`@Job`注解，配置任务名称、Cron表达式、负责人、异常告警等信息。
- 任务执行逻辑需添加详细日志，异常时发送告警通知，禁止静默失败。

### 报表规范
- 报表开发统一使用JImuReport工具，报表数据源统一配置，禁止直接连接生产数据库。
- 报表SQL需添加索引优化，避免大数据量查询时影响业务库性能。

## 八、依赖与构建规范
- Maven父工程统一管理所有子模块依赖版本，禁止子模块私自升级核心依赖版本。
- 新增第三方依赖需提交安全审批，避免引入高危安全漏洞。
- 模块间依赖需遵循单向依赖原则，禁止出现循环依赖，核心模块禁止依赖业务模块。
- 构建产物统一上传到内部Nexus仓库，禁止直接引用本地jar包。

## 九、日志与编码原则
### 日志规范
- 使用`@Slf4j`注解声明日志对象，禁止使用`System.out.println`输出日志。
- 日志级别需合理划分：`info`级别记录正常业务日志、`warn`级别记录异常但可恢复的日志、`error`级别记录系统异常日志，禁止生产环境输出`debug`级别日志。
- 日志中禁止输出敏感信息（密码、身份证号、手机号等）。

### 编码原则
| 原则       | 说明                                       |
|------------|--------------------------------------------|
| **SOLID**  | 高内聚、低耦合，增强可维护性与可扩展性     |
| **DRY**    | 避免重复代码，提高复用性，优先使用生成器生成代码 |
| **KISS**   | 保持代码简洁易懂，避免过度设计             |
| **YAGNI**  | 不实现当前不需要的功能，避免提前优化       |
| **OWASP**  | 防范常见安全漏洞，如SQL注入、XSS、越权访问等 |

### 版本管理规范
- 提交代码需遵循Git提交规范，提交信息以`feat`/`fix`/`docs`/`refactor`等前缀开头，说明提交类型。
- 核心模块代码提交需走Code Review流程，禁止直接提交到主分支。
