package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;

public interface Notifier {
    /**
     * 发送结构化交易信号通知。
     *
     * @param signal 交易信号
     */
    void notify(Signal signal);

    /**
     * 发送纯文本通知。
     *
     * @param message 文本内容
     */
    void notify(String message);
}
