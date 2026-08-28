/**
 * unibase 协作式线程调度验收 so(arm64):
 * 双线程 mutex 竞争 + nanosleep 让出 + join 等待。
 * run_threads() 返回 200 = 调度正确(每线程 100 次, 无丢失/死锁)。
 */
#include <pthread.h>
#include <string.h>

static volatile int counter = 0;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int worker_steps[2];

static void *worker(void *arg) {
    long idx = (long) arg;
    for (int i = 0; i < 100; i++) {
        pthread_mutex_lock(&lock);
        counter++;
        worker_steps[idx] = counter;
        pthread_mutex_unlock(&lock);
        struct timespec ts = {0, 1000000}; /* 1ms: nanosleep 让出点 */
        nanosleep(&ts, NULL);
    }
    return (void *) (idx + 1);
}

static pthread_t t1, t2;

int run_threads(void) {
    counter = 0;
    memset(worker_steps, 0, sizeof(worker_steps));
    if (pthread_create(&t1, NULL, worker, (void *) 0) != 0) return -1;
    if (pthread_create(&t2, NULL, worker, (void *) 1) != 0) return -2;
    void *r1 = NULL, *r2 = NULL;
    pthread_join(t1, &r1);
    pthread_join(t2, &r2);
    if ((long) r1 != 1 || (long) r2 != 2) return -3;
    return counter;
}
