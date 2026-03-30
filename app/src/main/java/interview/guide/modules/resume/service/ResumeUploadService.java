package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * AI 分析改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final AnalyzeStreamProducer analyzeStreamProducer;
    private final ResumeRepository resumeRepository;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    public Map<String, Object> uploadAndAnalyze(org.springframework.web.multipart.MultipartFile file) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        log.info("收到简历上传请求: {}, 大小: {} bytes", fileName, file.getSize());

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 3. 检查简历是否已存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            return handleDuplicateResume(existingResume.get());
        }

        // 4. 解析简历文本
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }

        // 5. 保存简历到RustFS
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {}", fileKey);

        // 6. 保存简历到数据库（状态为 PENDING）
        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);

        // 7. 发送分析任务到 Redis Stream（异步处理）
        analyzeStreamProducer.sendAnalyzeTask(savedResume.getId(), resumeText);

        log.info("简历上传完成，分析任务已入队: {}, resumeId={}", fileName, savedResume.getId());

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "analyzeStatus", AsyncTaskStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "duplicate", false
        );
    }

    /**
     * 【新增功能】批量上传并分析单人简历（每个文件一份简历）
     * 即功能2的①：上传多个可被分析的简历文件，其中每个简历文件中只有一个面试者信息
     *
     * @param files 多个简历文件列表
     * @return 批量处理的结果列表
     */
    public List<Map<String, Object>> batchUploadAndAnalyze(List<org.springframework.web.multipart.MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return new ArrayList<>();
        }

        // 使用 CompletableFuture 实现高阶多线程并发，极大地提升批量处理速度
        List<CompletableFuture<Map<String, Object>>> futures = files.stream()
            .map(file -> CompletableFuture.supplyAsync(() -> {
                String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "未知文件";
                try {
                    // 复用单文件的逻辑：安保校验 -> 解析文档 -> 哈希去重 -> 存库 -> 推送Redis
                    return uploadAndAnalyze(file);
                } catch (Exception e) {
                    // 局部容错机制：某一个文件失败不影响其他线程并行执行
                    log.error("批量上传时遇到错误文件 - 跳过该文件: {}", fileName, e);
                    
                    // 强制指定返回类型，以匹配 Map<String, Object> 的泛型要求
                    return Map.<String, Object>of(
                        "filename", fileName,
                        "status", AsyncTaskStatus.FAILED.name(),
                        "error", e.getMessage() != null ? e.getMessage() : "未知错误"
                    );
                }
            }))
            .toList();

        // 阻塞主线程，等待所有并发任务完成，并将结果收集成列表
        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    /**
     * 处理重复简历
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());

        // 获取历史分析结果
        Optional<ResumeAnalysisResponse> analysisOpt = persistenceService.getLatestAnalysisAsDTO(resume.getId());

        // 已有分析结果，直接返回
        // 没有分析结果（可能之前分析失败），返回当前状态
        return analysisOpt.map(resumeAnalysisResponse -> Map.of(
                "analysis", resumeAnalysisResponse,
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        )).orElseGet(() -> Map.of(
                "resume", Map.of(
                        "id", resume.getId(),
                        "filename", resume.getOriginalFilename(),
                        "analyzeStatus", resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus().name() : AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        ));
    }

    /**
     * 重新分析简历（手动重试）
     * 从数据库获取简历文本并发送分析任务
     *
     * @param resumeId 简历ID
     */
    @Transactional
    public void reanalyze(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, resume.getOriginalFilename());

        String resumeText = resume.getResumeText();
        if (resumeText == null || resumeText.trim().isEmpty()) {
            // 如果没有缓存的文本，尝试重新解析
            resumeText = parseService.downloadAndParseContent(resume.getStorageKey(), resume.getOriginalFilename());
            if (resumeText == null || resumeText.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            // 更新缓存的文本
            resume.setResumeText(resumeText);
        }

        // 更新状态为 PENDING
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resumeRepository.save(resume);

        // 发送分析任务到 Stream
        analyzeStreamProducer.sendAnalyzeTask(resumeId, resumeText);

        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }
}
