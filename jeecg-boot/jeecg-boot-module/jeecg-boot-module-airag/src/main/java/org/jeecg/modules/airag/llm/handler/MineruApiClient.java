package org.jeecg.modules.airag.llm.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.AssertUtils;
import org.jeecg.common.util.RestUtil;
import org.jeecg.common.util.UUIDGenerator;
import org.jeecg.common.util.filter.SsrfFileTypeFilter;
import org.jeecg.modules.airag.llm.config.KnowConfigBean;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MinerU 官方 API 客户端（精准解析 API）
 *
 * @Author: song
 * @Date: 2026-07-09
 */
@Slf4j
@Component
public class MineruApiClient {

    /**
     * 默认解析结果 Markdown 文件名
     */
    private static final String DEFAULT_MD_NAME = "full.md";

    /**
     * API 路径：批量申请本地文件上传链接并自动提交解析任务
     */
    private static final String API_FILE_URLS_BATCH = "/api/v4/file-urls/batch";

    /**
     * API 路径：批量查询解析结果
     */
    private static final String API_EXTRACT_RESULTS_BATCH = "/api/v4/extract-results/batch/%s";

    @Autowired
    private KnowConfigBean knowConfigBean;

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse方法签名扩展targetDir参数，images拷贝下沉到客户端内部-------
    /**
     * 解析文件并返回 Markdown 内容
     *
     * @param file     待解析文件
     * @param fileType 文件扩展名（不含点）
     * @param targetDir 业务目标目录（用于保存 full.md 与 images/）
     * @return Markdown 文本
     * @author song
     * @date 2026/7/9
     */
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse签名新增mdFileName参数，与EmbeddingHandler FILEPATH保持一致-------
    public String parse(File file, String fileType, File targetDir, String mdFileName) {
        KnowConfigBean.CloudConfig cloud = knowConfigBean.getMinerU().getCloud();
        AssertUtils.assertNotEmpty("请配置 MinerU 官方 API Key", cloud.getApiKey());
        AssertUtils.assertTrue("MinerU 官方 API 文件不能为空", file != null && file.exists());
        AssertUtils.assertTrue("MinerU 官方 API 目标目录不能为空", targetDir != null);
        AssertUtils.assertNotEmpty("MinerU 官方 API Markdown 目标文件名不能为空", mdFileName);
        //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse签名新增mdFileName参数，与EmbeddingHandler FILEPATH保持一致-------

        long startTime = System.currentTimeMillis();
        String batchId = null;
        try {
            // 1. 申请批量上传链接并自动创建解析任务
            BatchUploadInfo uploadInfo = applyBatchUploadUrl(file, fileType, cloud);
            batchId = uploadInfo.getBatchId();
            log.info("MinerU 官方 API 批量任务创建成功, batchId: {}, file: {}", batchId, file.getName());

            // 2. PUT 上传文件到 OSS 签名地址
            uploadFile(file, uploadInfo.getFileUrl(), cloud);
            log.info("MinerU 官方 API 文件上传成功, batchId: {}", batchId);

            // 3. 轮询批量任务结果
            BatchResult result = pollBatchResult(batchId, cloud);
            log.info("MinerU 官方 API 批量任务完成, batchId: {}, state: {}, cost: {}ms",
                    batchId, result.getState(), System.currentTimeMillis() - startTime);

            // 4. 下载并解压 ZIP，写入 targetDir 并拷贝 images/
            //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse签名新增mdFileName参数，与EmbeddingHandler FILEPATH保持一致-------
            String markdown = downloadAndExtractMarkdown(result.getFullZipUrl(), cloud, targetDir, mdFileName).markdown;
            //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse签名新增mdFileName参数，与EmbeddingHandler FILEPATH保持一致-------
            log.info("MinerU 官方 API 解析完成, batchId: {}, markdown length: {}, total cost: {}ms",
                    batchId, markdown == null ? 0 : markdown.length(), System.currentTimeMillis() - startTime);
            return markdown;
        } catch (JeecgBootException e) {
            log.error("MinerU 官方 API 调用失败, batchId: {}, cost: {}ms", batchId, System.currentTimeMillis() - startTime, e);
            throw e;
        } catch (Exception e) {
            log.error("MinerU 官方 API 调用异常, batchId: {}, cost: {}ms", batchId, System.currentTimeMillis() - startTime, e);
            throw new JeecgBootException("MinerU 官方 API 调用异常: " + e.getMessage(), e);
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API parse方法签名扩展targetDir参数，images拷贝下沉到客户端内部-------

    /**
     * 申请批量上传链接并自动提交解析任务
     *
     * @param file     待解析文件
     * @param fileType 文件扩展名
     * @param cloud    官方 API 配置
     * @return 批量上传信息
     * @author song
     * @date 2026/7/9
     */
    private BatchUploadInfo applyBatchUploadUrl(File file, String fileType, KnowConfigBean.CloudConfig cloud) {
        String url = buildApiUrl(cloud.getBaseUrl(), API_FILE_URLS_BATCH);

        // update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API精准解析请求参数（顶层 enable_formula/enable_table/language + file 级 is_ocr）-------
        JSONObject request = getJsonObject(file, fileType);
        // update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API精准解析请求参数（顶层 enable_formula/enable_table/language + file 级 is_ocr）-------

        HttpHeaders headers = buildAuthHeaders(cloud);
        HttpEntity<String> entity = new HttpEntity<>(request.toJSONString(), headers);
        log.info("MinerU 官方 API 创建批量任务, url: {}, fileName: {}", url, file.getName());

        RestTemplate restTemplate = createRestTemplate(cloud);
        ResponseEntity<JSONObject> response;
        try {
            response = restTemplate.exchange(URI.create(url), HttpMethod.POST, entity, JSONObject.class);
        } catch (RestClientResponseException e) {
            log.error("MinerU 官方 API 创建批量任务失败, status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new JeecgBootException("MinerU 官方 API 创建批量任务失败: " + resolveErrorMsg(e));
        }

        JSONObject body = response.getBody();
        if (body == null || !body.containsKey("data")) {
            throw new JeecgBootException("MinerU 官方 API 创建批量任务返回异常: " + body);
        }
        JSONObject data = body.getJSONObject("data");
        BatchUploadInfo result = new BatchUploadInfo();
        result.setBatchId(data.getString("batch_id"));
        JSONArray fileUrls = data.getJSONArray("file_urls");
        if (fileUrls == null || fileUrls.isEmpty()) {
            throw new JeecgBootException("MinerU 官方 API 未返回有效的 file_urls");
        }
        result.setFileUrl(fileUrls.getString(0));
        if (StringUtils.isEmpty(result.getBatchId()) || StringUtils.isEmpty(result.getFileUrl())) {
            throw new JeecgBootException("MinerU 官方 API 未返回有效的 batch_id 或 file_url");
        }
        return result;
    }

    @NotNull
    private static JSONObject getJsonObject(File file, String fileType) {
        JSONObject fileObj = new JSONObject();
        // 文件名（file 级必填）：含扩展名，便于官方识别文件类型
        fileObj.put("name", file.getName());
        // 是否启动 OCR（file 级可选，默认 false）：仅 PDF 显式关闭，因 PDF 通常已有文本层
        if ("pdf".equalsIgnoreCase(fileType)) {
            fileObj.put("is_ocr", true);
        }

        JSONObject request = new JSONObject();
        // 待上传文件数组（顶层必填）：批量本地上传场景，单文件也用此结构
        JSONArray filesArr = new JSONArray();
        filesArr.add(fileObj);
        request.put("files", filesArr);
        // 模型版本（顶层可选，默认 pipeline）：vlm 为通用推荐模型，支持公式/表格识别
        request.put("model_version", "vlm");
        // 是否开启公式识别（顶层可选，默认 true）：仅对 pipeline、vlm 模型有效
        request.put("enable_formula", true);
        // 是否开启表格识别（顶层可选，默认 true）：仅对 pipeline、vlm 模型有效
        request.put("enable_table", true);
        // 文档语言（顶层可选，默认 ch）：用于 OCR 识别；本仓库主要处理中文 PDF
        request.put("language", "ch");
        return request;
    }

    /**
     * PUT 上传文件到 OSS 签名地址
     *
     * @param file     本地文件
     * @param fileUrl  OSS 签名上传地址
     * @param cloud    官方 API 配置
     * @author song
     * @date 2026/7/9
     */
    //update-begin---author:song ---date:2026-07-10  for：【issues/9551】修复 MinerU OSS 签名上传不兼容 RestTemplate Content-Type-----------
    private void uploadFile(File file, String fileUrl, KnowConfigBean.CloudConfig cloud) {
        // 避免日志打印预签名 URL 内的临时签名参数
        log.info("MinerU 官方 API 开始上传文件, file: {}", file.getName());
        RestTemplate restTemplate = createRestTemplate(cloud);
        try {
            // 走 RestTemplate.execute 直接写 body，避免 ByteArrayHttpMessageConverter
            // 自动补 Content-Type: application/octet-stream，从而与 MinerU OSS 预签名 URL
            // 签名时的请求头保持一致（官方要求“无须设置 Content-Type 请求头”）。
            restTemplate.execute(URI.create(fileUrl), HttpMethod.PUT, request -> {
                request.getHeaders().setContentLength(file.length());
                Files.copy(file.toPath(), request.getBody());
            }, response -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new JeecgBootException("MinerU 官方 API 文件上传失败, HTTP状态码: " + response.getStatusCode());
                }
                return null;
            });
        } catch (RestClientResponseException e) {
            log.error("MinerU 官方 API 文件上传失败, status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new JeecgBootException("MinerU 官方 API 文件上传失败: " + resolveErrorMsg(e));
        }
    }
    //update-end---author:song ---date:2026-07-10  for：【issues/9551】修复 MinerU OSS 签名上传不兼容 RestTemplate Content-Type-----------

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效，避免retryTimes计算超出timeout--------
    /**
     * 轮询批量任务结果直到完成或失败
     *
     * @param batchId 批量任务 ID
     * @param cloud   官方 API 配置
     * @return 批量任务结果
     * @author song
     * @date 2026/7/9
     */
    private BatchResult pollBatchResult(String batchId, KnowConfigBean.CloudConfig cloud) {
        String url = buildApiUrl(cloud.getBaseUrl(), String.format(API_EXTRACT_RESULTS_BATCH, batchId));
        HttpHeaders headers = buildAuthHeaders(cloud);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        RestTemplate restTemplate = createRestTemplate(cloud);

        int maxRetry = Math.max(cloud.getRetryTimes(), 1);
        int interval = Math.max(cloud.getRetryInterval(), 1);
        long startTime = System.currentTimeMillis();
        long maxWaitMillis = cloud.getTimeout() * 1000L;
        for (int i = 0; i < maxRetry; i++) {
            if (System.currentTimeMillis() - startTime >= maxWaitMillis) {
                throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");
            }
            log.debug("MinerU 官方 API 轮询批量任务结果, batchId: {}, 第{}次", batchId, i + 1);
            ResponseEntity<JSONObject> response;
            try {
                response = restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, JSONObject.class);
            } catch (RestClientResponseException e) {
                log.error("MinerU 官方 API 查询批量任务结果失败, batchId: {}, status: {}, body: {}",
                        batchId, e.getStatusCode(), e.getResponseBodyAsString());
                throw new JeecgBootException("MinerU 官方 API 查询批量任务结果失败: " + resolveErrorMsg(e));
            }

            JSONObject body = response.getBody();
            if (body == null || !body.containsKey("data")) {
                throw new JeecgBootException("MinerU 官方 API 查询批量任务结果返回异常: " + body);
            }
            JSONObject data = body.getJSONObject("data");
            JSONArray extractResult = data.getJSONArray("extract_result");
            if (extractResult == null || extractResult.isEmpty()) {
                throw new JeecgBootException("MinerU 官方 API 批量任务结果为空");
            }
            JSONObject first = extractResult.getJSONObject(0);
            BatchResult result = new BatchResult();
            result.setBatchId(data.getString("batch_id"));
            result.setState(first.getString("state"));
            result.setFullZipUrl(first.getString("full_zip_url"));
            result.setErrMsg(first.getString("err_msg"));

            if ("done".equalsIgnoreCase(result.getState())) {
                if (StringUtils.isEmpty(result.getFullZipUrl())) {
                    throw new JeecgBootException("MinerU 官方 API 批量任务完成但未返回结果下载地址");
                }
                return result;
            }
            if ("failed".equalsIgnoreCase(result.getState())) {
                throw new JeecgBootException("MinerU 官方 API 批量任务执行失败: " + result.getErrMsg());
            }

            if (System.currentTimeMillis() - startTime + interval * 1000L >= maxWaitMillis) {
                throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时（超过 " + cloud.getTimeout() + " 秒）");
            }
            try {
                TimeUnit.SECONDS.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JeecgBootException("MinerU 官方 API 轮询被中断", e);
            }
        }
        throw new JeecgBootException("MinerU 官方 API 批量任务轮询超时，请稍后重试");
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API总超时实际生效，避免retryTimes计算超出timeout--------

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API直接解压到targetDir并拷贝images目录，避免双写--------
    /**
     * 下载 ZIP 并解压读取 Markdown，并把结果落到指定业务目录
     *
     * @param fullZipUrl 结果 ZIP 下载地址
     * @param cloud      官方 API 配置
     * @param targetDir  业务目标目录（用于保存 full.md 与 images/）
     * @return 解压结果（Markdown 文本与 images 目录路径）
     * @author song
     * @date 2026/7/9
     */
    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API downloadAndExtractMarkdown签名新增mdFileName参数，images目录从images改为auto与Local模式一致-------
    private ExtractionResult downloadAndExtractMarkdown(String fullZipUrl, KnowConfigBean.CloudConfig cloud, File targetDir, String mdFileName) {
        //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API downloadAndExtractMarkdown签名新增mdFileName参数，images目录从images改为auto与Local模式一致-------
        // 创建临时目录
        String tmpDir = System.getProperty("java.io.tmpdir") + File.separator + "mineru" + File.separator + UUIDGenerator.generate();
        Path tmpPath = Paths.get(tmpDir);
        try {
            Files.createDirectories(tmpPath);
        } catch (IOException e) {
            throw new JeecgBootException("创建 MinerU 临时目录失败: " + tmpDir, e);
        }

        String zipPath = tmpDir + File.separator + "result.zip";
        log.info("MinerU 官方 API 开始下载结果 ZIP, url: {}, 保存路径: {}", fullZipUrl, zipPath);
        try {
            downloadZip(fullZipUrl, zipPath, cloud);
            ExtractionResult result = extractMarkdown(zipPath, tmpDir);

            // 将临时解压结果落到目标目录：写 full.md + 拷贝 images/
            if (targetDir != null && !targetDir.exists() && !targetDir.mkdirs()) {
                throw new JeecgBootException("创建 MinerU 官方 API 目标目录失败: " + targetDir);
            }
            //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API downloadAndExtractMarkdown签名新增mdFileName参数，images目录从images改为auto与Local模式一致-------
            File mdFile = new File(targetDir, mdFileName);
            //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API downloadAndExtractMarkdown签名新增mdFileName参数，images目录从images改为auto与Local模式一致-------
            try {
                FileUtils.writeStringToFile(mdFile, result.markdown, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new JeecgBootException("写入 MinerU 官方 API Markdown 结果失败: " + mdFile, e);
            }
            if (StringUtils.isNotEmpty(result.imagesDir)) {
                File srcImages = new File(result.imagesDir);
                //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API downloadAndExtractMarkdown签名新增mdFileName参数，images目录从images改为auto与Local模式一致-------
                File destImages = new File(targetDir, "auto");
                //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API downloadAndExtractMarkdown签名新增mdFileName参数，images目录从images改为auto与Local模式一致-------
                if (srcImages.isDirectory()) {
                    try {
                        FileUtils.copyDirectory(srcImages, destImages);
                        log.info("MinerU 官方 API 图片资源已拷贝, src: {}, dest: {}", srcImages, destImages);
                    } catch (IOException e) {
                        log.warn("MinerU 官方 API 图片资源拷贝失败: {}", destImages, e);
                    }
                }
            }
            return new ExtractionResult(result.markdown, result.imagesDir);
        } finally {
            // 清理临时文件
            try {
                FileUtils.deleteDirectory(tmpPath.toFile());
            } catch (IOException e) {
                log.warn("清理 MinerU 临时目录失败: {}", tmpDir, e);
            }
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API直接解压到targetDir并拷贝images目录--------

    /**
     * 下载 ZIP 文件
     *
     * @param fullZipUrl ZIP 下载地址
     * @param savePath   本地保存路径
     * @param cloud      官方 API 配置
     * @author song
     * @date 2026/7/9
     */
    private void downloadZip(String fullZipUrl, String savePath, KnowConfigBean.CloudConfig cloud) {
        // 路径安全校验
        SsrfFileTypeFilter.checkPathTraversal(savePath);

        RestTemplate restTemplate = createRestTemplate(cloud);
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(URI.create(fullZipUrl), HttpMethod.GET, entity, byte[].class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new JeecgBootException("MinerU 官方 API 结果 ZIP 下载失败, HTTP状态码: " + response.getStatusCode());
            }
            FileUtils.writeByteArrayToFile(new File(savePath), response.getBody());
        } catch (RestClientResponseException e) {
            log.error("MinerU 官方 API 结果 ZIP 下载失败, status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 下载失败: " + resolveErrorMsg(e));
        } catch (IOException e) {
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 保存失败: " + savePath, e);
        }
    }

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果改返回ExtractionResult-------
    /**
     * 解压 ZIP 并读取 Markdown 内容
     *
     * @param zipPath ZIP 文件路径
     * @param outDir  解压目录
     * @return Markdown 文本与 images 目录路径（如果存在）
     * @author song
     * @date 2026/7/9
     */
    private ExtractionResult extractMarkdown(String zipPath, String outDir) {
        File zipFile = new File(zipPath);
        if (!zipFile.exists()) {
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 不存在: " + zipPath);
        }

        String mdPath = null;
        boolean hasImagesDir = false;
        try (ZipFile zip = new ZipFile(zipFile, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                File entryFile = new File(outDir, entryName);
                // 防止 ZIP 路径遍历
                if (!entryFile.getCanonicalPath().startsWith(new File(outDir).getCanonicalPath() + File.separator)) {
                    throw new JeecgBootException("MinerU 官方 API 结果 ZIP 包含非法路径: " + entryName);
                }

                if (entry.isDirectory()) {
                    FileUtils.forceMkdir(entryFile);
                    continue;
                }
                FileUtils.forceMkdir(entryFile.getParentFile());
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(entryFile)) {
                    IOUtils.copy(in, out);
                }
                if (DEFAULT_MD_NAME.equalsIgnoreCase(FilenameUtils.getName(entryName))) {
                    mdPath = entryFile.getAbsolutePath();
                }
                if (entryName.contains("images/") || entryName.startsWith("images" + File.separator)) {
                    hasImagesDir = true;
                }
            }
        } catch (IOException e) {
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 解压失败: " + zipPath, e);
        }

        if (StringUtils.isEmpty(mdPath)) {
            throw new JeecgBootException("MinerU 官方 API 结果 ZIP 中未找到 " + DEFAULT_MD_NAME);
        }

        String markdown;
        try {
            markdown = FileUtils.readFileToString(new File(mdPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JeecgBootException("MinerU 官方 API 读取 Markdown 结果失败: " + mdPath, e);
        }

        String imagesDir = hasImagesDir ? outDir + File.separator + "images" : null;
        return new ExtractionResult(markdown, imagesDir);
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果改返回ExtractionResult-------

    /**
     * 构建带鉴权的请求头
     *
     * @param cloud 官方 API 配置
     * @return HttpHeaders
     * @author song
     * @date 2026/7/9
     */
    private HttpHeaders buildAuthHeaders(KnowConfigBean.CloudConfig cloud) {
        HttpHeaders headers = RestUtil.getHeaderApplicationJson();
        if (StringUtils.isNotEmpty(cloud.getApiKey())) {
            headers.setBearerAuth(cloud.getApiKey());
        }
        return headers;
    }

    /**
     * 构建完整 API URL
     *
     * @param baseUrl 基地址
     * @param apiPath 接口路径
     * @return 完整 URL
     * @author song
     * @date 2026/7/9
     */
    private String buildApiUrl(String baseUrl, String apiPath) {
        String url = StringUtils.removeEnd(baseUrl, "/");
        return url + apiPath;
    }

    /**
     * 创建带超时的 RestTemplate
     *
     * @param cloud 官方 API 配置
     * @return RestTemplate
     * @author song
     * @date 2026/7/9
     */
    private RestTemplate createRestTemplate(KnowConfigBean.CloudConfig cloud) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectionRequestTimeout(cloud.getConnectTimeout() * 1000);
        factory.setReadTimeout(cloud.getReadTimeout() * 1000);
        return new RestTemplate(factory);
    }

    /**
     * 解析 RestClientResponseException 中的错误信息
     *
     * @param e 异常
     * @return 错误描述
     * @author song
     * @date 2026/7/9
     */
    private String parseErrorMsg(RestClientResponseException e) {
        if (e == null) {
            return "未知错误";
        }
        String body = e.getResponseBodyAsString();
        if (StringUtils.isNotEmpty(body)) {
            try {
                JSONObject json = JSON.parseObject(body);
                if (json.containsKey("msg")) {
                    return json.getString("msg");
                }
                if (json.containsKey("message")) {
                    return json.getString("message");
                }
            } catch (Exception ex) {
                // 非 JSON 响应直接返回 body
            }
            return body;
        }
        return e.getStatusCode() + " " + e.getStatusText();
    }

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API异常信息优先取错误码映射表，兜底取官方msg字段--------
    /**
     * 解析 RestClientResponseException 中的错误信息，优先使用错误码映射表
     *
     * @param e 异常
     * @return 错误描述
     * @author song
     * @date 2026/7/9
     */
    private String resolveErrorMsg(RestClientResponseException e) {
        if (e == null) {
            return "未知错误";
        }
        String body = e.getResponseBodyAsString();
        String translated = MineruErrorCodeMapper.translate(body);
        if (translated != null) {
            return translated;
        }
        return parseErrorMsg(e);
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API异常信息优先取错误码映射表，兜底取官方msg字段--------

    /**
     * 批量上传信息内部类
     */
    private static class BatchUploadInfo {
        private String batchId;
        private String fileUrl;

        public String getBatchId() {
            return batchId;
        }

        public void setBatchId(String batchId) {
            this.batchId = batchId;
        }

        public String getFileUrl() {
            return fileUrl;
        }

        public void setFileUrl(String fileUrl) {
            this.fileUrl = fileUrl;
        }
    }

    /**
     * 批量任务结果内部类
     */
    private static class BatchResult {
        private String batchId;
        private String state;
        private String fullZipUrl;
        private String errMsg;

        public String getBatchId() {
            return batchId;
        }

        public void setBatchId(String batchId) {
            this.batchId = batchId;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getFullZipUrl() {
            return fullZipUrl;
        }

        public void setFullZipUrl(String fullZipUrl) {
            this.fullZipUrl = fullZipUrl;
        }

        public String getErrMsg() {
            return errMsg;
        }

        public void setErrMsg(String errMsg) {
            this.errMsg = errMsg;
        }
    }

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果封装（含markdown文本与images目录路径）-------
    /**
     * MinerU 官方 API 解压结果
     */
    private static class ExtractionResult {
        final String markdown;
        final String imagesDir;

        ExtractionResult(String markdown, String imagesDir) {
            this.markdown = markdown;
            this.imagesDir = imagesDir;
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API解压结果封装-------

    //update-begin---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示，复用JeecgBootException-------
    /**
     * MinerU 官方 API 错误码 → 中文业务提示映射表
     */
    private static class MineruErrorCodeMapper {
        private static final Map<String, String> CODE_MSG = new HashMap<>();
        static {
            // 鉴权类
            CODE_MSG.put("A0202", "MinerU API Key 不正确，请检查 Token 是否包含 Bearer 前缀或重新生成");
            CODE_MSG.put("A0211", "MinerU API Key 已过期，请在 mineru.net 后台重新生成 Token");
            // 通用
            CODE_MSG.put("-500", "MinerU 请求参数错误，请联系管理员检查请求格式");
            CODE_MSG.put("-10001", "MinerU 服务暂时异常，请稍后重试");
            CODE_MSG.put("-10002", "MinerU 请求参数错误，请检查参数格式");
            // 上传类
            CODE_MSG.put("-60001", "MinerU 生成上传链接失败，请稍后重试");
            CODE_MSG.put("-60002", "MinerU 不支持的文件格式，仅支持 PDF/Doc/Docx/Ppt/Pptx/Xls/Xlsx 及常见图片格式");
            CODE_MSG.put("-60003", "MinerU 文件读取失败，文件可能损坏");
            CODE_MSG.put("-60004", "MinerU 不支持空文件");
            CODE_MSG.put("-60005", "MinerU 文件大小超过 200MB 限制，请拆分文件后重试");
            CODE_MSG.put("-60006", "MinerU 文件页数超过 200 页限制，请拆分文件后重试");
            CODE_MSG.put("-60007", "MinerU 模型服务暂时不可用，请稍后重试或联系技术支持");
            CODE_MSG.put("-60008", "MinerU 文件读取超时，请检查 URL 可访问性");
            CODE_MSG.put("-60011", "MinerU 获取有效文件失败，请确保文件已上传");
            // 配额类
            CODE_MSG.put("-60017", "MinerU 重试次数达到上限，请稍后重试");
            CODE_MSG.put("-60018", "MinerU 每日解析任务数量已达上限，请明日再试");
            CODE_MSG.put("-60019", "MinerU html 文件解析额度不足，请明日再试");
        }

        /**
         * 解析官方错误响应为 Jeecg 风格业务提示
         * @param body 响应体（JSON）
         * @return 翻译后的中文提示；若无法识别则返回 null
         */
        static String translate(String body) {
            if (StringUtils.isEmpty(body)) {
                return null;
            }
            try {
                JSONObject json = JSON.parseObject(body);
                Object code = json.get("code");
                if (code == null) {
                    return null;
                }
                return CODE_MSG.get(String.valueOf(code));
            } catch (Exception e) {
                return null;
            }
        }
    }
    //update-end---author:song ---date:2026-07-09  for：【AI知识库】MinerU官方API错误码映射为中文业务提示-------

}
