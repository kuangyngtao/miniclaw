package com.clawkit.reliability;

import com.clawkit.tools.control.CancelRegistration;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.TokenBudget;
import com.clawkit.tools.control.WorkBudget;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 级联取消树：{@link ExecutionControl} 的生产实现。
 *
 * <p>父节点 cancel 会级联到所有子节点并触发各自的取消回调；
 * 子节点的 deadline 取父与自身的较小值；token 预算默认与父共享同一账本，
 * 子节点只能通过 {@link BudgetLedger#childCapped(long)} 获得更小配额。
 */
public final class CancellationTree implements ExecutionControl {

    private static final Logger log = LoggerFactory.getLogger(CancellationTree.class);

    private final Instant deadline;          // 有效 deadline（已并入父约束），可为 null
    private final TokenBudget budget;
    private final WorkBudget workBudget;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<CancellationTree> children = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private CancellationTree(Instant deadline, TokenBudget budget, WorkBudget workBudget,
                             boolean initiallyCancelled) {
        this.deadline = deadline;
        this.budget = budget != null ? budget : TokenBudget.unlimited();
        this.workBudget = workBudget != null ? workBudget : WorkBudget.unlimited();
        this.cancelled.set(initiallyCancelled);
    }

    /** 根节点：deadline / budget 均可为 null（无限制）。 */
    public static CancellationTree root(Instant deadline, TokenBudget budget) {
        return new CancellationTree(deadline, budget, null, false);
    }

    public static CancellationTree root(Instant deadline, TokenBudget budget,
                                        WorkBudget workBudget) {
        return new CancellationTree(deadline, budget, workBudget, false);
    }

    /** 无限制根节点。 */
    public static CancellationTree unbounded() {
        return new CancellationTree(null, null, null, false);
    }

    /**
     * 从任意 ExecutionControl 派生子树：继承取消信号、deadline 和同一预算账本。
     * 父已取消时子树立即处于取消态（禁止在取消后 fork 新任务）。
     */
    public static CancellationTree childOf(ExecutionControl parent) {
        if (parent instanceof CancellationTree tree) {
            return tree.child(null, null);
        }
        CancellationTree child = new CancellationTree(
            parent.deadline().orElse(null), parent.tokenBudget(), parent.workBudget(),
            parent.isCancelled());
        // 非树实现无法挂 children，依赖 onCancel 回调级联
        parent.onCancel(child::cancel);
        return child;
    }

    /**
     * 创建子节点。
     *
     * @param maxTime  子节点额外的时间上限（与父 deadline 取较小），null 表示仅继承父约束
     * @param maxTokens 子节点 token 配额上限（仍从共享账本扣除），null 表示直接共享父账本
     */
    public CancellationTree child(Duration maxTime, Long maxTokens) {
        Instant childDeadline = deadline;
        if (maxTime != null) {
            Instant own = Instant.now().plus(maxTime);
            childDeadline = childDeadline == null || own.isBefore(childDeadline) ? own : childDeadline;
        }
        TokenBudget childBudget = budget;
        if (maxTokens != null && budget instanceof BudgetLedger ledger) {
            childBudget = ledger.childCapped(maxTokens);
        } else if (maxTokens != null) {
            childBudget = BudgetLedger.of(maxTokens);
        }
        CancellationTree child = new CancellationTree(
            childDeadline, childBudget, workBudget, cancelled.get());
        children.add(child);
        return child;
    }

    /** 取消：级联到所有子节点，触发全部已注册回调（每个回调最多一次）。 */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        for (Listener l : listeners) {
            l.fire();
        }
        for (CancellationTree child : children) {
            child.cancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public Optional<Instant> deadline() {
        return Optional.ofNullable(deadline);
    }

    @Override
    public TokenBudget tokenBudget() {
        return budget;
    }

    @Override
    public WorkBudget workBudget() {
        return workBudget;
    }

    @Override
    public CancelRegistration onCancel(Runnable action) {
        Listener listener = new Listener(action);
        if (cancelled.get()) {
            listener.fire();
            return CancelRegistration.noop();
        }
        listeners.add(listener);
        // 注册与取消竞态：加入后再检查一次
        if (cancelled.get()) {
            listener.fire();
        }
        return () -> listeners.remove(listener);
    }

    /** 单次触发的取消回调，异常不阻断级联。 */
    private static final class Listener {
        private final Runnable action;
        private final AtomicBoolean fired = new AtomicBoolean(false);

        Listener(Runnable action) {
            this.action = action;
        }

        void fire() {
            if (!fired.compareAndSet(false, true)) return;
            try {
                action.run();
            } catch (Exception e) {
                log.warn("cancel listener failed: {}", e.getMessage());
            }
        }
    }
}
