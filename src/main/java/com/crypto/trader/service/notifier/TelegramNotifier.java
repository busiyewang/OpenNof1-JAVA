package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Telegram Bot 通知器。
 *
 * <p>调用 Telegram Bot API 的 sendMessage 接口将交易信号推送到指定群组或频道。
 * 若 bot-token 或 chat-id 未配置，则降级为仅打印日志。</p>
 *
 * <p>环境变量：TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TelegramNotifier implements Notifier {

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org";

    @Value("${crypto.notifier.telegram.bot-token:}")
    private String botToken;

    @Value("${crypto.notifier.telegram.chat-id:}")
    private String chatId;

    private final WebClient.Builder webClientBuilder;

    @Override
    public void notify(Signal signal) {
        String message = String.format(
                "📊 *交易信号*\n策略: %s\n交易对: %s\n动作: *%s*\n价格: %.4f\n置信度: %.2f\n原因: %s",
                signal.getStrategyName(),
                signal.getSymbol(),
                signal.getAction(),
                signal.getPrice(),
                signal.getConfidence(),
                signal.getReason());
        notify(message);
    }

    @Override
    public void notify(String subject, String message) {
        // Telegram 不区分标题，合并发送
        notify(subject + "\n\n" + message);
    }

    @Override
    public void notify(String message) {
        if (!StringUtils.hasText(botToken) || botToken.startsWith("your-")
                || !StringUtils.hasText(chatId) || chatId.startsWith("your-")) {
            log.info("[TelegramNotifier] Not configured, logging only: {}", message);
            return;
        }

        try {
            WebClient client = webClientBuilder.baseUrl(TELEGRAM_API_BASE).build();

            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "text", message,
                    "parse_mode", "Markdown"
            );

            String url = "/bot" + botToken + "/sendMessage";
            client.post()
                    .uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("[TelegramNotifier] Message sent to chat {}", chatId);
        } catch (Exception e) {
            log.error("[TelegramNotifier] Failed to send message", e);
        }
    }
}
