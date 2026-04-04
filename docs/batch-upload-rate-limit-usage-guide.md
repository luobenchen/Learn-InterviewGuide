# 批量上传与限流修复说明文档（使用与排障）

## 1. 文档目的

本说明文档用于指导以下事项：

1. 如何正确使用批量上传接口。
2. 如何判断当前报错是限流、方法错误还是文件校验错误。
3. 如何在 Docker 环境中确保修复生效。

---

## 2. 功能说明

### 2.1 批量上传接口
- 方法：POST
- 路径：/api/resumes/batch-upload
- 内容类型：multipart/form-data
- 字段名：files（可重复添加多个）

### 2.2 返回语义
- code=200：请求成功（即使其中部分文件失败，也会在 data 中逐条给出状态）。
- code=429：触发限流，请稍后重试。
- code=405：请求方法不支持（通常为路径正确但 HTTP 方法错误）。
- code=500：系统内部异常（非预期，需看后端日志）。

---

## 3. 浏览器侧使用说明

1. 打开上传页并一次选择多个文件。
2. 页面会显示文件列表。
3. 点击开始上传。
4. 若失败，页面将展示后端 message。

前端关键实现位置：

- 多文件状态与多选输入：[interview-guide-master/frontend/src/components/FileUploadCard.tsx](interview-guide-master/frontend/src/components/FileUploadCard.tsx#L55)
- 批量 API 调用：[interview-guide-master/frontend/src/api/resume.ts](interview-guide-master/frontend/src/api/resume.ts#L17)
- 页面批量分支：[interview-guide-master/frontend/src/pages/UploadPage.tsx](interview-guide-master/frontend/src/pages/UploadPage.tsx#L20)

---

## 4. Postman 使用说明

### 4.1 正确请求方式
1. Method 选择 POST。
2. URL 填写 http://localhost:8080/api/resumes/batch-upload。
3. Body 选择 form-data。
4. 添加多个 files 字段，每个字段类型为 File。

### 4.2 常见错误对照
1. 错误：POST 到 /api/resumes。
2. 结果：code=405。
3. 原因：该路径主要用于 GET 列表查询。
4. 错误：上传 .md 导致失败。
5. 结果：文件级失败，提示不支持的文件类型。
6. 原因：后端文件类型校验限制。
7. 错误：短时间高频调用。
8. 结果：code=429。
9. 原因：触发限流规则。

---

## 5. 后端规则说明

### 5.1 限流处理
- 位置：[interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java#L44)
- 行为：限流异常统一转换为 429 业务码。

### 5.2 方法不支持处理
- 位置：[interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java#L54)
- 行为：方法错误转换为 405 业务码并提示支持的方法。

### 5.3 批量接口限流阈值
- 位置：[interview-guide-master/app/src/main/java/interview/guide/modules/resume/ResumeController.java](interview-guide-master/app/src/main/java/interview/guide/modules/resume/ResumeController.java#L61)
- 配置：count=20。

---

## 6. Docker 生效说明

若代码已改但行为不变，优先排查是否仍运行旧镜像。

推荐步骤：

1. 进入目录 c:/LJS/java/interview-guide-master/interview-guide-master。
2. 执行 docker compose build app。
3. 执行 docker compose up -d app。
4. 查看 docker ps 确认 interview-app 已重建。

前端有改动时也需要重建 frontend 服务。

---

## 7. 现场排障清单

出现异常时按顺序检查：

1. URL 是否为 /api/resumes/batch-upload。
2. Method 是否为 POST。
3. form-data 字段名是否为 files。
4. 上传文件类型是否在后端允许范围。
5. 是否高频触发限流（观察是否 429）。
6. 是否忘记重建并重启 app 容器。
7. 通过 docker logs interview-app --tail 200 查看真实异常。

---

## 8. 变更文件总览

后端：
- [interview-guide-master/app/src/main/java/interview/guide/common/exception/ErrorCode.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/ErrorCode.java#L19)
- [interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java](interview-guide-master/app/src/main/java/interview/guide/common/exception/GlobalExceptionHandler.java#L44)
- [interview-guide-master/app/src/main/java/interview/guide/modules/resume/ResumeController.java](interview-guide-master/app/src/main/java/interview/guide/modules/resume/ResumeController.java#L61)

前端：
- [interview-guide-master/frontend/src/components/FileUploadCard.tsx](interview-guide-master/frontend/src/components/FileUploadCard.tsx#L55)
- [interview-guide-master/frontend/src/api/resume.ts](interview-guide-master/frontend/src/api/resume.ts#L17)
- [interview-guide-master/frontend/src/pages/UploadPage.tsx](interview-guide-master/frontend/src/pages/UploadPage.tsx#L20)
