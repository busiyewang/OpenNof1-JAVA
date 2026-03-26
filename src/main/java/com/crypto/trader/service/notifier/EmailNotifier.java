package com.crypto.trader.service.notifier;

import com.crypto.trader.model.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class EmailNotifier implements Notifier {

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

    @Override
    public void notify(String subject, String message) {
        sendEmail(subject, message);
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
