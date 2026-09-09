/* emu_probe.c — unibase 反模拟检测面探针(看雪 thread-292882 四探测点)
 *
 * 纯 freestanding(无 libc), 驱动经 Symbol 调 run_probes(), 结果读 probe_result()。
 * 探测点:
 *   bit0 CNTVCT 恒定   bit1 CNTPCT 恒定   (真机: 计数器随硬件推进)
 *   bit2 EL0 读 EL1-only 寄存器存活      (真机: 架构强制 UNDEF → SIGILL)
 *   bit3 LDP 同寄存器对存活              (真机: CONSTRAINED UNPREDICTABLE, 实测全 SIGILL)
 *   bit4 CASP 奇偶错配对存活             (真机: UNDEFINED, 实测全 SIGILL)
 * probes 2~4 在真机上会杀死进程 —— 本 so 仅用于模拟器暴露面测量。
 */
typedef unsigned long long u64;
typedef unsigned int u32;

typedef struct {
    u32 magic;        /* 'UBPR' */
    u32 detections;   /* bit0..bit4, 见上 */
    u32 done;         /* 0xDEADBEEF = 全部探测执行完毕 */
    u32 reserved;
    u64 cntfrq;
    u64 cntvct_a, cntvct_b;
    u64 cntpct_a, cntpct_b;
    u64 sctlr_el1;
    u64 ldp_value;
    u64 casp_out0, casp_out1;
} probe_result_t;

static probe_result_t g_result = { 0x52504255u, 0, 0, 0, 0, 0,0, 0,0, 0, 0, 0,0 };
static volatile u64 g_spin;
static u64 g_casp_mem[2] __attribute__((aligned(16)));

#define READ_SYSREG(name) ({ u64 v; __asm__ volatile("mrs %0, " name : "=r"(v)); v; })

__attribute__((visibility("default"))) probe_result_t *probe_result(void) { return &g_result; }

/* mode 位控: bit0=probe1(计时器) bit1=probe2(EL1 MRS) bit2=probe3(LDP) bit3=probe4(CASP)
 * 驱动用二分法定位各后端的致命探测点; 跳过的探测记入 skip 位(高 16 位)。 */
__attribute__((visibility("default"))) int run_probes(unsigned mode) {
    probe_result_t *r = &g_result;
    if (!(mode & 1u)) r->detections |= 1u << 16;
    if (!(mode & 2u)) r->detections |= 1u << 17;
    if (!(mode & 4u)) r->detections |= 1u << 18;
    if (!(mode & 8u)) r->detections |= 1u << 19;

    if (mode & 1u) {

    /* probe1: 计时器恒定性 —— 20 万次忙等, 前后读虚拟计数器 */
    r->cntvct_a = READ_SYSREG("cntvct_el0");
    r->cntpct_a = READ_SYSREG("cntpct_el0");
    for (g_spin = 0; g_spin < 200000u; g_spin++) { }
    r->cntvct_b = READ_SYSREG("cntvct_el0");
    r->cntpct_b = READ_SYSREG("cntpct_el0");
    if (r->cntvct_b == r->cntvct_a) r->detections |= 1u << 0;
    if (r->cntpct_b == r->cntpct_a) r->detections |= 1u << 1;
    }

    if (mode & 2u) {
    /* probe2: EL0 读 EL1-only —— 存活到这一行即证明无权限隔离 */
    {
        u64 v;
        __asm__ volatile("mrs %0, sctlr_el1" : "=r"(v));
        r->sctlr_el1 = v;
    }
    r->detections |= 1u << 2;
    }

    if (mode & 4u) {
    /* probe3: LDP x9,x9,[x10] —— 同寄存器对, 执行到取值即证明无译码校验。
     * clang 汇编器直接拒收("unpredictable LDP"), 手工编码 0xA9402549。 */
    {
        u64 addr = (u64)&g_spin;
        u64 val;
        __asm__ volatile(
            "mov x10, %1 \n\t"
            ".word 0xA9402549 /* ldp x9, x9, [x10] */ \n\t"
            "mov %0, x9"
            : "=r"(val)
            : "r"(addr)
            : "x9", "x10", "memory");
        r->ldp_value = val;
    }
    r->detections |= 1u << 3;
    }

    if (mode & 8u) {
    /* probe4: CASP 奇偶错配对(x1,x2) —— 编码只存偶号寄存器(Rs 场), 奇号即
     * UNDEFINED; 真机全 SIGILL。合法基字 casp x0,x1,x2,x3,[x4]=0x48207C82,
     * Rs 场 [20:16] 置 1 得 0x48217C82。执行到读结果即证明无对齐校验。 */
    {
        u64 *m = g_casp_mem;
        u64 o0 = 0, o1 = 0;
        __asm__ volatile(
            "mov x4, %2 \n\t"
            ".word 0x48217C82 /* casp x1, x2, x2, x3, [x4] */ \n\t"
            "str x1, %0 \n\t"
            "str x2, %1"
            : "=m"(o0), "=m"(o1)
            : "r"(m)
            : "x1", "x2", "x3", "x4", "memory");
        r->casp_out0 = o0;
        r->casp_out1 = o1;
    }
    r->detections |= 1u << 4;
    }

    r->done = 0xDEADBEEFu;
    return (int)r->detections;
}
