package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import com.github.unidbg.signal.SigSet;
import com.github.unidbg.signal.SignalOps;
import com.github.unidbg.signal.SignalTask;
import com.github.unidbg.signal.UnixSigSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抢占式调度
 */
public class UniThreadDispatcher implements ThreadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UniThreadDispatcher.class);

    private final List<Task> taskList = new ArrayList<>();
    private final AbstractEmulator<?> emulator;

    public UniThreadDispatcher(AbstractEmulator<?> emulator) {
        this.emulator = emulator;
    }

    private final List<ThreadTask> threadTaskList = new ArrayList<>();

    @Override
    public void addThread(ThreadTask task) {
        threadTaskList.add(task);
    }

    @Override
    public List<Task> getTaskList() {
        return taskList;
    }

    @Override
    public boolean sendSignal(int tid, int sig, SignalTask signalTask) {
        List<Task> list = new ArrayList<>();
        list.addAll(taskList);
        list.addAll(threadTaskList);
        boolean ret = false;
        for (Task task : list) {
            SignalOps signalOps = null;
            if (tid == 0 && task.isMainThread()) {
                signalOps = this;
            }
            if (tid == task.getId()) {
                signalOps = task;
            }
            if (signalOps == null) {
                continue;
            }
            SigSet sigSet = signalOps.getSigMaskSet();
            SigSet sigPendingSet = signalOps.getSigPendingSet();
            if (sigPendingSet == null) {
                sigPendingSet = new UnixSigSet(0);
                signalOps.setSigPendingSet(sigPendingSet);
            }
            if (sigSet != null && sigSet.containsSigNumber(sig)) {
                sigPendingSet.addSigNumber(sig);
                return false;
            }
            if (signalTask != null) {
                task.addSignalTask(signalTask);
                if (log.isTraceEnabled()) {
                    emulator.attach().debug("Signal dispatched: sig=" + sig);
                }
            } else {
                sigPendingSet.addSigNumber(sig);
            }
            ret = true;
            break;
        }
        return ret;
    }

    private RunnableTask runningTask;

    @Override
    public RunnableTask getRunningTask() {
        return runningTask;
    }

    @Override
    public Number runMainForResult(MainTask main) {
        taskList.add(0, main);

        log.debug("runMainForResult main={}", main);

        Number ret = run(0, null);
        for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
            Task task = iterator.next();
            if (task.isFinish()) {
                log.debug("Finish task={}", task);
                task.destroy(emulator);
                iterator.remove();
                for (SignalTask signalTask : task.getSignalTaskList()) {
                    signalTask.destroy(emulator);
                    task.removeSignalTask(signalTask);
                }
            }
        }
        return ret;
    }

    @Override
    public void runThreads(long timeout, TimeUnit unit) {
        if (timeout <= 0 || unit == null) {
            throw new IllegalArgumentException("Invalid timeout.");
        }
        run(timeout, unit);
    }

    private Number run(long timeout, TimeUnit unit) {
        Number mainRet = null;
        boolean mainDone = false;
        // drain 保护(P3): 主任务完成后, 后台线程调度有时间预算与无进展退出 ——
        // 防止常驻线程(心跳/上报类)或跑飞线程导致无限调度 hang 死
        final long drainBudgetMs = Long.getLong("unibase.threads.drainTimeout", 10000L);
        long mainDoneAt = 0;
        int idleRounds = 0;
        try {
            long start = System.currentTimeMillis();
            while (true) {
                // 先把新创建的后台线程搬入调度队列(主任务完成后 drain 的关键 ——
                // 此前 threadTaskList 永远不会被搬入, 子线程让出后也无人继续调度)
                Collections.reverse(threadTaskList);
                for (Iterator<ThreadTask> it = threadTaskList.iterator(); it.hasNext(); ) {
                    taskList.add(0, it.next());
                    it.remove();
                }
                if (taskList.isEmpty() && mainDone) {
                    // 主任务已完成且无剩余任务 —— 正常收尾
                    return mainRet;
                }
                if (taskList.isEmpty()) {
                    if (mainDone) {
                        return mainRet;
                    }
                    throw new IllegalStateException();
                }
                if (mainDone && drainBudgetMs > 0
                        && System.currentTimeMillis() - mainDoneAt > drainBudgetMs) {
                    log.info("drain timeout ({}ms), {} background task(s) abandoned",
                            drainBudgetMs, taskList.size());
                    return mainRet;
                }
                int dispatchedThisRound = 0;
                for (Iterator<Task> iterator = taskList.iterator(); iterator.hasNext(); ) {
                    Task task = iterator.next();
                    if (task.isFinish()) {
                        continue;
                    }
                    if (task.canDispatch()) {
                        log.debug("Start dispatch task={}", task);
                        emulator.set(Task.TASK_KEY, task);

                        if(task.isContextSaved()) {
                            task.restoreContext(emulator);
                            for (SignalTask signalTask : task.getSignalTaskList()) {
                                if (signalTask.canDispatch()) {
                                    log.debug("Start run signalTask={}", signalTask);
                                    SignalOps ops = task.isMainThread() ? this : task;
                                    try {
                                        this.runningTask = signalTask;
                                        Number ret = signalTask.callHandler(ops, emulator);
                                        log.debug("End run signalTask={}, ret={}", signalTask, ret);
                                        if (ret != null) {
                                            signalTask.setResult(emulator, ret);
                                            signalTask.destroy(emulator);
                                            task.removeSignalTask(signalTask);
                                        } else {
                                            signalTask.saveContext(emulator);
                                        }
                                    } catch (PopContextException e) {
                                        this.runningTask.popContext(emulator);
                                    }
                                } else {
                                    log.debug("Skip call handler signalTask={}", signalTask);
                                }
                            }
                        }

                        try {
                            this.runningTask = task;
                            dispatchedThisRound++;
                            Number ret = task.dispatch(emulator);
                            log.debug("End dispatch task={}, ret={}", task, ret);
                            if (ret != null) {
                                task.setResult(emulator, ret);
                                task.destroy(emulator);
                                iterator.remove();
                                if(task.isMainThread()) {
                                    // 主任务完成(P3): 不再立即返回 —— 继续调度已创建的
                                    // 后台线程直至自然结束(SO 异步初始化依赖此语义),
                                    // mainRet 在循环退出条件处返回
                                    mainRet = ret;
                                    mainDone = true;
                                    mainDoneAt = System.currentTimeMillis();
                                }
                            } else {
                                task.saveContext(emulator);
                            }
                        } catch(PopContextException e) {
                            this.runningTask.popContext(emulator);
                        }
                    } else {
                        if (log.isTraceEnabled() && task.isContextSaved()) {
                            task.restoreContext(emulator);
                            log.trace("Skip dispatch task={}", task);
                            emulator.getUnwinder().unwind();
                        } else {
                            log.debug("Skip dispatch task={}", task);
                        }
                    }
                }

                if (mainDone) {
                    // 无进展保护: 本轮没有任何任务可调度(全部 waiter 阻塞/挂起) → 退出
                    if (dispatchedThisRound == 0) {
                        if (++idleRounds >= 3) {
                            for (Task t : taskList) {
                                log.info("abandoned: {}, waiter={}, saved={}",
                                        t + " waiter=" + t.getWaiter() + " saved=" + t.isContextSaved());
                            }
                            log.info("drain idle: no runnable background task, {} abandoned",
                                    taskList.size());
                            return mainRet;
                        }
                    } else {
                        idleRounds = 0;
                    }
                }
                dispatchedThisRound = 0;

                if (timeout > 0 && unit != null &&
                        System.currentTimeMillis() - start >= unit.toMillis(timeout)) {
                    return mainRet;
                }

                if (log.isDebugEnabled()) {
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } finally {
            this.runningTask = null;
            emulator.set(Task.TASK_KEY, null);
        }
    }

    @Override
    public int getTaskCount() {
        return taskList.size() + threadTaskList.size();
    }

    private SigSet mainThreadSigMaskSet;
    private SigSet mainThreadSigPendingSet;

    @Override
    public SigSet getSigMaskSet() {
        return mainThreadSigMaskSet;
    }

    @Override
    public void setSigMaskSet(SigSet sigMaskSet) {
        this.mainThreadSigMaskSet = sigMaskSet;
    }

    @Override
    public SigSet getSigPendingSet() {
        return mainThreadSigPendingSet;
    }

    @Override
    public void setSigPendingSet(SigSet sigPendingSet) {
        this.mainThreadSigPendingSet = sigPendingSet;
    }
}
