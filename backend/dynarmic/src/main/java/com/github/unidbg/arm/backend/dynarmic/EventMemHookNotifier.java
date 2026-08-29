package com.github.unidbg.arm.backend.dynarmic;

import com.github.unidbg.arm.backend.Backend;
import com.github.unidbg.arm.backend.EventMemHook;
import unicorn.UnicornConst;

public class EventMemHookNotifier {

    private final EventMemHook callback;
    private final int type;
    private final Object user_data;

    public EventMemHookNotifier(EventMemHook callback, int type, Object user_data) {
        this.callback = callback;
        this.type = type;
        this.user_data = user_data;
    }

    /** @return hook 是否已处理(通常已 lazy 映射); false 时 native 侧 WARN+假值继续 */
    public boolean handleMemoryReadFailed(Backend backend, long vaddr, int size) {
        if ((type & UnicornConst.UC_HOOK_MEM_READ_UNMAPPED) != 0) {
            return callback.hook(backend, vaddr, size, 0, user_data, EventMemHook.UnmappedType.Read);
        }
        return false;
    }

    public boolean handleMemoryWriteFailed(Backend backend, long vaddr, int size) {
        if ((type & UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED) != 0) {
            return callback.hook(backend, vaddr, size, 0, user_data, EventMemHook.UnmappedType.Write);
        }
        return false;
    }
}
