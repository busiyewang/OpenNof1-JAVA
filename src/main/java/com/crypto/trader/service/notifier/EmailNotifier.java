package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件通知器。
 *
 * <p>使用 Spring {@link JavaMailSender} 发送交易信号邮件。
 * 若邮件服务未配置（spring.mail.host 未设置），JavaMailSender bean 不会创建，
 * 此时注入为 null，通知器自动降级为仅打印日志。</p>
 *
 * <p>环境变量：MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM, MAIL_TO</p>
 */
@Service
@Slf4j
public class EmailNotifier implements Notifier {

    /** JavaMailSender 可能未配置，允许为 null（@Autowired required=false）。 */
    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${crypto.notifier.email.from:}")
    private String from;

    @Value("${crypto.notifier.email.to:}")
    private String to;

    @Override
    public void notify(Signal signal) {
        String subject = String.format("[%s] %s %s 信号",
                signal.getStrategyName(), signal.getSymbol(), signal.getAction());
        String body = String.format(
                "策略: %s\n交易对: %s\n动作: %s\n价格: %.4f\n置信度: %.2f\n原因: %s",
                signal.getStrategyName(),
                signal.getSymbol(),
                signal.getAction(),
                signal.getPrice(),
                signal.getConfidence(),
                signal.getReason());
        sendEmail(subject, body);
    }

    @Override
    public void notify(String message) {
        sendEmail("交易机器人通知", message);
    }

    private void sendEmail(String subject, String body) {
        if (mailSender == null) {
            log.info("[EmailNotifier] JavaMailSender not configured, logging only: {}", subject);
            return;
        }
        if (!StringUtils.hasText(from) || from.startsWith("your-")
                || !StringUtils.hasText(to) || to.startsWith("your-")) {
            log.info("[EmailNotifier] from/to not configured, logging only: {}", subject);
            return;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(to);
            mail.setSubject(subject);
            mail.setText(body);
            mailSender.send(mail);
            log.info("[EmailNotifier] Email sent to {}: {}", to, subject);
        } catch (Exception e) {
            log.error("[EmailNotifier] Failed to send email: {}", subject, e);
        }
    }
}
