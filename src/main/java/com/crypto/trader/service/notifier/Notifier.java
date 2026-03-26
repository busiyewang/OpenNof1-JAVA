package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;

public interface Notifier {
    /**
     * 发送结构化交易信号通知。
     */
    void notify(Signal signal);

    /**
     * 发送纯文本通知（默认标题）。
     */
    void notify(String message);

    /**
     * 发送带自定义标题的文本通知。
     */
    void notify(String subject, String message);
}
