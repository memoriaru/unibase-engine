package com.github.unidbg.linux.android.dvm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** FallbackJni 语义: 未覆写 JNI 回调 → 聚合 WARN + 类型化默认值, 不抛异常。 */
public class FallbackJniTest {

    @Test
    public void unimplementedMethodsReturnTypedDefaults() {
        FallbackJni jni = new FallbackJni() {};

        assertEquals(0, jni.callStaticIntMethodV(null, null, "test/x->a()I", null));
        assertEquals(0L, jni.callStaticLongMethodV(null, null, "test/x->b()J", null));
        assertEquals(false, jni.callStaticBooleanMethodV(null, null, "test/x->c()Z", null));
        assertEquals(0, jni.callIntMethodV(null, null, "test/x->d()I", null));
        assertNull(jni.callObjectMethodV(null, null, "test/x->e()V", null));
        assertNull(jni.callStaticObjectMethodV(null, null, "test/x->f()V", null));
        assertEquals(0, jni.getIntField(null, null, "test/x->g()I"));
        // void/写操作静默通过
        jni.callStaticVoidMethodV(null, null, "test/x->h()V", null);
        jni.setIntField(null, null, "test/x->i()V", 42);
    }

    @Test
    public void subclassOverrideWins() {
        FallbackJni jni = new FallbackJni() {
            @Override
            public int callStaticIntMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
                return 77;
            }
        };
        assertEquals(77, jni.callStaticIntMethodV(null, null, "test/x->a()I", null));
    }
}
