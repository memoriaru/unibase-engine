package com.github.unidbg.arm.backend;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/** 能力协商(P3): 后端精确声明, 调用方装 hook 前查询。 */
public class CapabilityTest {

    @Test
    public void dynarmicDeclaresNarrowCapabilities() {
        // DynarmicBackend 实测: Code/Read/Write/Block hook 与 debugger 不可用;
        // 快照原语(context_*)可用
        java.util.Set<Capability> caps = DynarmicBackend.CAPABILITIES;
        assertTrue(caps.contains(Capability.CONTEXT_SNAPSHOT));
        assertTrue(caps.contains(Capability.EVENT_MEM_HOOK));
        assertTrue(caps.contains(Capability.INTERRUPT_HOOK));
        assertFalse(caps.contains(Capability.CODE_HOOK));
        assertFalse(caps.contains(Capability.READ_HOOK));
        assertFalse(caps.contains(Capability.WRITE_HOOK));
        assertFalse(caps.contains(Capability.BLOCK_HOOK));
        assertFalse(caps.contains(Capability.DEBUG_HOOK));
    }

    @Test
    public void interfaceDefaultIsAllCapabilities() {
        // 未声明的后端(接口 default)保持全能力语义 —— 引入能力协商前行为不变
        assertEquals(java.util.EnumSet.allOf(Capability.class), Capability.all());
    }
}
