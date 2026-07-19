package com.clawkit.tools.control;

/**
 * 取消回调注册句柄。阻塞操作结束后必须 close()，防止迟到中断打到无关工作。
 */
public interface CancelRegistration extends AutoCloseable {

    /** 幂等注销；不抛受检异常。 */
    @Override
    void close();

    static CancelRegistration noop() {
        return NoopRegistration.INSTANCE;
    }
}

final class NoopRegistration implements CancelRegistration {
    static final NoopRegistration INSTANCE = new NoopRegistration();
    private NoopRegistration() {}
    @Override public void close() {}
}
