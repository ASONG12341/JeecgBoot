package org.jeecg.modules.airag.llm.handler;

import com.sun.net.httpserver.HttpServer;
import org.jeecg.modules.airag.llm.config.KnowConfigBean;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MinerU 官方 API 上传签名 URL 时，PUT 请求不得携带 Content-Type 请求头。
 *
 * 背景：阿里 OSS 预签名 URL 在签名时假定请求头与生成签名时一致。
 * 官方文档明确要求“上传文件时，无须设置 Content-Type 请求头”
 * （见 jeecg-boot-module-airag/docs/官方数据.md）。
 * 当前实现通过 RestTemplate exchange byte[] 上传，
 * 会经 ByteArrayHttpMessageConverter 自动补 Content-Type: application/octet-stream，
 * 导致 OSS 报 SignatureDoesNotMatch。
 */
class MineruApiClientUploadFileTest {

    @Test
    void putToOssPresignedUrlShouldNotSendContentTypeHeader() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>("not-captured");
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<Long> bodyLength = new AtomicReference<>(0L);
        CountDownLatch requestLatch = new CountDownLatch(1);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try (exchange) {
                long read = 0;
                byte[] buf = new byte[1024];
                java.io.InputStream in = exchange.getRequestBody();
                int n;
                while ((n = in.read(buf)) > 0) {
                    read += n;
                }
                bodyLength.set(read);
                byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } finally {
                requestLatch.countDown();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        String presignedUrl = "http://127.0.0.1:" + port + "/api-upload/extract/test.pdf?x-oss-signature=stub";

        File temp = File.createTempFile("mineru-upload-test", ".pdf");
        temp.deleteOnExit();
        Files.write(temp.toPath(), "hello-mineru".getBytes(StandardCharsets.UTF_8));

        KnowConfigBean.CloudConfig cloud = new KnowConfigBean.CloudConfig();
        cloud.setConnectTimeout(5);
        cloud.setReadTimeout(5);
        cloud.setBaseUrl("http://127.0.0.1");
        cloud.setApiKey("test-key");

        MineruApiClient client = new MineruApiClient();
        Method uploadFile = MineruApiClient.class.getDeclaredMethod("uploadFile", File.class, String.class, KnowConfigBean.CloudConfig.class);
        uploadFile.setAccessible(true);
        try {
            uploadFile.invoke(client, temp, presignedUrl, cloud);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Exception) {
                throw new RuntimeException(cause);
            }
            throw ite;
        }

        assertTrue(requestLatch.await(5, TimeUnit.SECONDS), "本地 HTTP 服务端未收到 PUT 请求");
        assertNotNull(method.get(), "未捕获到 HTTP 方法");
        assertTrue("PUT".equalsIgnoreCase(method.get()), "HTTP 方法应为 PUT");
        assertNotNull(bodyLength.get(), "未捕获到请求体长度");
        assertTrue(bodyLength.get() > 0, "PUT 请求体不应为空");
        // 关键断言：OSS 预签名 URL 校验时，PUT 请求中不应包含 Content-Type 请求头
        assertNull(contentType.get(), "上传到 OSS 预签名 URL 时不应发送 Content-Type 请求头，实际为: " + contentType.get());

        server.stop(0);
    }
}
