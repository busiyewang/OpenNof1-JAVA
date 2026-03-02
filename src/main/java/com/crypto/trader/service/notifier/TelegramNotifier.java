package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TelegramNotifier implements Notifier {

    @Value("${crypto.notifier.telegram.bot-token:}")
    private String botToken;

    @Value("${crypto.notifier.telegram.chat-id:}")
    private String chatId;

    /**
     * 将交易信号格式化为可读文本并发送通知。
     *
     * @param signal 交易信号
     */
    @Override
    public void notify(Signal signal) {
        String message = String.format("[%s] %s %s at %.2f (confidence: %.2f) - %s",
                signal.getStrategyName(), signal.getSymbol(), signal.getAction(),
                signal.getPrice(), signal.getConfidence(), signal.getReason());
        notify(message);
    }

    /**
     * 发送 Telegram 文本通知。
     *
     * <p>当前实现仅记录日志；如需真正发送，需要调用 Telegram Bot API，
     * 并使用 {@code botToken}/{@code chatId} 完成鉴权与目标路由。</p>
     *
     * @param message 文本内容
     */
    @Override
    public void notify(String message) {
        log.info("Telegram notification: {}", message);
        // 实际调用 Telegram API
    }
}
