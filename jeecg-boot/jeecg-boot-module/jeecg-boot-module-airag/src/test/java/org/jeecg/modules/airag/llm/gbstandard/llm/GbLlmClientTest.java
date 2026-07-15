package org.jeecg.modules.airag.llm.gbstandard.llm;

import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.service.IAiragModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbLlmClient 共享 LLM 客户端测试-----------
@ExtendWith(MockitoExtension.class)
class GbLlmClientTest {

    @Mock
    private IAiragModelService airagModelService;

    @Test
    void resolveApiKeyShouldParseJsonCredential() {
        // 验证 {"apiKey":"sk-xxx"} 格式解析
        GbLlmClient client = new GbLlmClient(airagModelService);
        assertThat(client.resolveApiKey("{\"apiKey\":\"sk-xxx\"}")).isEqualTo("sk-xxx");
    }

    @Test
    void resolveApiKeyShouldReturnPlaintextAsIs() {
        GbLlmClient client = new GbLlmClient(airagModelService);
        assertThat(client.resolveApiKey("sk-plaintext")).isEqualTo("sk-plaintext");
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P2】GbLlmClient 共享 LLM 客户端测试-----------
