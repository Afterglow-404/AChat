package com.aftglw.devapi.core.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterFieldsTest {

    @Test
    fun `空 persona 返回空字段`() {
        val fields = CharacterFields.fromPersona("")
        assertTrue(fields.description.isEmpty())
        assertTrue(fields.personality.isEmpty())
        assertTrue(fields.scenario.isEmpty())
        assertTrue(fields.mesExample.isEmpty())
        assertTrue(fields.systemPrompt.isEmpty())
    }

    @Test
    fun `纯文本旧格式归入 description`() {
        val persona = "你是一个傲娇的猫娘喵，说话带喵。"
        val fields = CharacterFields.fromPersona(persona)
        assertEquals(persona, fields.description)
        assertTrue(fields.personality.isEmpty())
    }

    @Test
    fun `完整标签往返序列化`() {
        val original = CharacterFields(
            description = "名叫小白的猫娘",
            personality = "傲娇、爱撒娇、嘴硬心软",
            scenario = "和主人一起住在公寓里",
            mesExample = "用户：你好\n小白：哼，才不是特意等你回来的喵！",
            systemPrompt = "请用第一人称回复"
        )
        val persona = original.toPersona()
        val parsed = CharacterFields.fromPersona(persona)
        assertEquals(original, parsed)
    }

    @Test
    fun `部分字段为空时不输出对应标签`() {
        val fields = CharacterFields(description = "只有描述", personality = "", scenario = "", mesExample = "", systemPrompt = "")
        val persona = fields.toPersona()
        assertTrue(persona.contains("<description>只有描述</description>"))
        assertTrue(!persona.contains("<personality>"))
        assertTrue(!persona.contains("<scenario>"))
    }

    @Test
    fun `未闭合标签取到字符串末尾`() {
        val persona = "<description>没闭合的描述"
        val fields = CharacterFields.fromPersona(persona)
        assertEquals("没闭合的描述", fields.description)
    }

    @Test
    fun `标签内容含换行符正确解析`() {
        val original = CharacterFields(
            description = "第一行\n第二行\n第三行",
            personality = "性格A\n性格B"
        )
        val parsed = CharacterFields.fromPersona(original.toPersona())
        assertEquals(original.description, parsed.description)
        assertEquals(original.personality, parsed.personality)
    }

    @Test
    fun `标签内含 XML 特殊字符不转义也能解析`() {
        val original = CharacterFields(description = "包含 < 和 > 符号的描述")
        // 注意：如果描述内含 </description> 会被截断，这是已知限制
        val persona = original.toPersona()
        val parsed = CharacterFields.fromPersona(persona)
        assertEquals(original.description, parsed.description)
    }

    @Test
    fun `混合旧格式和新标签 优先解析标签`() {
        val persona = "前面的纯文本<description>真正的描述</description>"
        val fields = CharacterFields.fromPersona(persona)
        assertEquals("真正的描述", fields.description)
    }
}
