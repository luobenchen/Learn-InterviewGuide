# 批量上传与限流问题修复实现文档（含错误复盘）

## 1. 背景与目标

### 1.1 背景
在批量上传简历场景中，用户在 Postman 与浏览器端均出现了统一错误返回：

- code: 500
- message: 系统繁忙，请稍后重试

同时前端页面在刷新后仍表现为仅支持单文件上传。

### 1.2 目标
- 修复前端批量选择与批量提交能力。
- 修复后端限流异常的返回语义，避免误报为通用 500。
- 修复请求方法不匹配导致的误导性错误提示。
- 确保 Docker 运行环境加载的是最新代码。

---

## 2. 最终实现概览

本次完成了前后端与部署链路三部分改造：

1. 前端改造：将单文件状态改为多文件数组状态，并对批量上传接口进行调用。
2. 后端改造：
   - 增加 METHOD_NOT_ALLOWED 业务码（405）。
   - 增加 HttpRequestMethodNotSupportedException 专门处理器。
   - 保持限流异常走独立处理器并返回 429 语义。
   - 批量上传接口限流阈值由 5 提升到 20。
3. 部署改造：强制重建并重启 app 容器，解决旧镜像导致的“代码已改、运行未改”问题。

---

## 3. 代码变更明细

### 3.1 后端异常码与异常处理

#### 3.1.1 新增 405 业务码
- 文件：[interview-guide-master/app/src/main/java/interview/guide/common/exception/ErrorCode.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/ErrorCode.java#L19)
- 变更：新增 METHOD_NOT_ALLOWED(405, "请求方法不支持")。

#### 3.1.2 限流异常返回调整
- 文件：[interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java#L44)
- 变更：handleRateLimitExceededException 返回 TOO_MANY_REQUESTS 业务码与标准文案，避免显示“系统繁忙”。

#### 3.1.3 请求方法不支持异常专门处理
- 文件：[interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java#L54)
- 变更：新增 handleHttpRequestMethodNotSupportedException。
- 效果：当接口仅支持 GET 却收到 POST 时，返回 405 业务码与清晰提示，不再落入通用 500。

### 3.2 批量上传接口限流阈值

- 文件：[interview-guide-master/app/src/main/java/interview/guide/modules/resume/ResumeController.java](interview-guide-master/app/src/main/java/interview/guide/modules/resume/ResumeController.java#L61)
- 变更：/api/resumes/batch-upload 的限流 count 从 5 提高到 20。

### 3.3 前端批量上传能力

#### 3.3.1 上传组件状态从单文件改为多文件
- 文件：[interview-guide-master/frontend/src/components/FileUploadCard.tsx](interview-guide-master/frontend/src/components/FileUploadCard.tsx#L55)
- 关键点：selectedFiles 采用 File[]，拖拽与文件选择均支持数组。
- 关键点：input 开启 multiple，见 [interview-guide-master/frontend/src/components/FileUploadCard.tsx](interview-guide-master/frontend/src/components/FileUploadCard.tsx#L158)。

#### 3.3.2 前端 API 增加批量接口调用
- 文件：[interview-guide-master/frontend/src/api/resume.ts](interview-guide-master/frontend/src/api/resume.ts#L17)
- 关键点：batchUploadAndAnalyze 将多个文件以 files 字段追加到 FormData。
- 路径：/api/resumes/batch-upload，见 [interview-guide-master/frontend/src/api/resume.ts](interview-guide-master/frontend/src/api/resume.ts#L22)。

#### 3.3.3 页面上传流程识别数组输入
- 文件：[interview-guide-master/frontend/src/pages/UploadPage.tsx](interview-guide-master/frontend/src/pages/UploadPage.tsx#L20)
- 关键点：当参数是文件数组时走批量上传分支。

---

## 4. 错误内容与原因复盘

本节按时间序列描述关键错误、原因与修复动作。

### 4.1 错误一：持续出现 500 系统繁忙

#### 现象
- 返回体：code=500, message=系统繁忙，请稍后重试。

#### 根因
不是单一限流问题，实际由以下因素叠加：

1. 请求方法不匹配：
   - 日志出现 HttpRequestMethodNotSupportedException。
   - 例如对 /api/resumes 使用 POST，而该接口只支持 GET。
2. 全局异常处理缺少专门分支：
   - 方法不支持异常落入了 Exception 通用处理，统一返回系统繁忙。

#### 修复
- 新增 405 错误码与方法不支持异常处理器，避免误导性 500。

### 4.2 错误二：前端刷新后仍只能上传一个文件

#### 现象
- 组件有 multiple，但实际状态只保留了单个文件。

#### 根因
前端状态逻辑采用单文件模型，导致 UI 与 input 属性不一致。

#### 修复
- 将状态与上传回调统一改为 File[]，并提供多文件列表渲染与移除功能。

### 4.3 错误三：代码已改但行为不变

#### 现象
- 本地文件已修复，接口行为仍像旧版本。

#### 根因
运行中的 Docker app 容器使用旧镜像，容器内 app.jar 时间戳早于本次修复。

#### 修复
- 执行 docker compose build app 与 docker compose up -d app，替换为新镜像后行为恢复一致。

### 4.4 错误四：Java 编译出现乱码与字符串未闭合

#### 现象
- 编译报错包含：\ufeff、String literal is not properly closed。

#### 根因
终端批量替换过程中引入 BOM 与编码破坏，导致中文字符串被损坏。

#### 修复
- 回退受污染文件并重新按源码方式注入变更。
- 重新编译验证通过。

### 4.5 错误五：联调脚本误判

#### 现象
- 测试上传 .md 文件返回失败。

#### 根因
后端文件类型校验拒绝 text/x-web-markdown，属于业务校验失败，不是服务故障。

#### 修复
- 使用 sample-resume.txt 进行回归，接口返回 200。

---

## 5. 为什么会错（能力与流程层面）

### 5.1 忽略了运行态与代码态一致性
初期更多聚焦源码逻辑，未第一时间验证容器镜像是否最新，导致“修复正确但未生效”的错觉。

### 5.2 对异常链路的覆盖不完整
初期将 500 主要归因于限流，后续日志证明还存在方法不匹配异常被兜底吞没，说明对全局异常分流覆盖考虑不足。

### 5.3 接口契约校验不足
在错误复现阶段，存在将错误请求（方法、路径）与限流问题混合观察，增加了定位噪声。

### 5.4 环境细节预判不足
PowerShell 5.1 与常见 shell 在命令能力上存在差异（如 curl、-Form 支持），增加了复现实验成本。

---

## 6. 验证结果

### 6.1 批量上传接口
- 请求：POST /api/resumes/batch-upload（files）
- 结果：code=200，返回批量数据结构，上传成功。

### 6.2 错误方法调用
- 请求：POST /api/resumes
- 结果：code=405，提示当前方法与支持方法，不再返回 500。

### 6.3 限流触发
- 对 /api/resumes/upload 快速连续请求 8 次。
- 结果：1 到 5 次返回 200，第 6 到 8 次返回 429。

---

## 7. 运维与交付说明

### 7.1 必要部署步骤
1. 进入内层项目根目录：
   - c:/LJS/java/interview-guide-master/interview-guide-master
2. 重建后端镜像：
   - docker compose build app
3. 重启后端容器：
   - docker compose up -d app
4. 前端如有构建变更，同样重建与重启：
   - docker compose build frontend
   - docker compose up -d frontend

### 7.2 请求规范
- 批量上传必须使用：POST /api/resumes/batch-upload
- 表单字段名必须为：files
- 不应对 /api/resumes 发 POST。

---

## 8. 后续建议

1. 为 GlobalExceptionHandler 增加更多框架异常的专门处理分支，减少通用 500。
2. 在 README 增加“Docker 代码改动后必须重建镜像”的显式说明。
3. 增加批量上传与限流行为的集成测试，覆盖 200、405、429 三类结果。
4. 在前端上传页面明确展示后端返回 code 与 message，便于快速定位。
