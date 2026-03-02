package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailNotifier implements Notifier {
    /**
     * 发送结构化交易信号邮件通知。
     *
     * <p>当前实现仅记录日志；如需真正发信，需要接入 SMTP 或第三方邮件服务。</p>
     *
     * @param signal 交易信号
     */
    @Override
    public void notify(Signal signal) {
        log.info("Email notification: {}", signal);
    }

    /**
     * 发送文本邮件通知。
     *
     * <p>当前实现仅记录日志；如需真正发信，需要接入 SMTP 或第三方邮件服务。</p>
     *
     * @param message 文本内容
     */
    @Override
    public void notify(String message) {
        log.info("Email notification: {}", message);
    }
}
