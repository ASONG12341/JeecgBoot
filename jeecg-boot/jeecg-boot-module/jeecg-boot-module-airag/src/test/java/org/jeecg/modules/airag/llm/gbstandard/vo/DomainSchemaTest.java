package org.jeecg.modules.airag.llm.gbstandard.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

//update-begin---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】DomainSchema 序列化测试-----------
class DomainSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldSerializeFourSlotsWithLabelEnumUnit() throws Exception {
        DomainSchema schema = new DomainSchema();
        DomainSchema.Slot primary = new DomainSchema.Slot();
        primary.setLabel("测试类型");
        primary.setEnumValues(Arrays.asList("overcharge", "short_circuit", "crush"));
        schema.setPrimaryType(primary);

        DomainSchema.Slot quantity = new DomainSchema.Slot();
        quantity.setLabel("电池串数");
        quantity.setUnit("S");
        schema.setQuantityValue(quantity);

        String json = mapper.writeValueAsString(schema);
        assertThat(json).contains("\"primaryType\"");
        assertThat(json).contains("\"测试类型\"");
        assertThat(json).contains("\"overcharge\"");
        assertThat(json).contains("\"quantityValue\"");
        assertThat(json).contains("\"S\"");
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        String json = "{\"primaryType\":{\"label\":\"测试类型\",\"enumValues\":[\"overcharge\"]}}";
        DomainSchema schema = mapper.readValue(json, DomainSchema.class);
        assertThat(schema.getPrimaryType().getLabel()).isEqualTo("测试类型");
        assertThat(schema.getPrimaryType().getEnumValues()).contains("overcharge");
        // 未提供的槽位应为 null（领域无关：不是所有标准都填满 4 槽位）
        assertThat(schema.getSecondaryType()).isNull();
    }

    @Test
    void emptySchemaShouldSerializeAsEmptyObject() throws Exception {
        DomainSchema schema = new DomainSchema();
        String json = mapper.writeValueAsString(schema);
        // fallback 场景：LLM 推不出 schema，退化为空对象，不阻塞入库
        assertThat(mapper.readTree(json).size()).isEqualTo(0);
    }
}
//update-end---author:song ---date:2026-07-15  for：【GB-RAG v4 P1】DomainSchema 序列化测试-----------
