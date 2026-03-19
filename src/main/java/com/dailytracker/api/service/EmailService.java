package com.dailytracker.api.service;

import com.dailytracker.api.entity.NotificationSchedule;
import com.dailytracker.api.entity.Task;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.notifications.from-email}")
    private String fromEmail;

    @Value("${app.notifications.from-name}")
    private String fromName;

    @Value("${app.notifications.enabled:true}")
    private boolean notificationsEnabled;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("UTC"));

    @Transactional
    public void sendDueDateNotification(NotificationSchedule schedule, Task task) {
        if (!notificationsEnabled) {
            log.debug("Notifications disabled — skipping schedule id={}", schedule.getId());
            return;
        }

        try {
            String html = buildEmailHtml(task, schedule);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(schedule.getRecipientEmail());
            helper.setSubject("[DailyTracker] Prazo da tarefa: " + task.getTitle());
            helper.setText(html, true);
            mailSender.send(message);

            schedule.setStatus("SENT");
            schedule.setSentAt(Instant.now());
            log.info("Notification sent: scheduleId={} to={}", schedule.getId(), schedule.getRecipientEmail());

        } catch (Exception ex) {
            log.error("Failed to send notification scheduleId={}: {}", schedule.getId(), ex.getMessage());
            int retries = schedule.getRetryCount() + 1;
            schedule.setRetryCount(retries);
            schedule.setErrorMessage(ex.getMessage() != null
                    ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 500))
                    : "Unknown error");
            if (retries >= 3) {
                schedule.setStatus("FAILED");
            } else {
                schedule.setNextRetryAt(Instant.now().plusSeconds(60));
            }
        }
    }

    private String buildEmailHtml(Task task, NotificationSchedule schedule) {
        String dueDateFormatted = task.getDueDate() != null
                ? DATE_FMT.format(task.getDueDate())
                : "—";

        String projectName = task.getProject() != null ? task.getProject().getName() : null;
        String workspaceName = task.getWorkspace() != null ? task.getWorkspace().getName() : "";
        String taskLink = frontendUrl + "/dashboard?taskId=" + task.getId();

        String projectRow = projectName != null
                ? "<tr><td style='padding:4px 0;color:#6b7280;font-size:14px;'>Projeto</td>"
                  + "<td style='padding:4px 0;font-size:14px;'>" + escapeHtml(projectName) + "</td></tr>"
                : "";

        String description = (task.getDescription() != null && !task.getDescription().isBlank())
                ? "<p style='margin:16px 0 0;color:#374151;font-size:14px;'>"
                  + escapeHtml(task.getDescription()) + "</p>"
                : "";

        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='margin:0;padding:0;background:#f9fafb;font-family:Arial,sans-serif;'>"
                + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f9fafb;padding:32px 0;'>"
                + "<tr><td align='center'>"
                + "<table width='600' cellpadding='0' cellspacing='0' style='background:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.1);'>"
                + "<tr><td style='background:#4f46e5;padding:24px 32px;'>"
                + "<span style='color:#ffffff;font-size:20px;font-weight:700;'>DailyTracker</span>"
                + "</td></tr>"
                + "<tr><td style='padding:32px;'>"
                + "<h2 style='margin:0 0 8px;color:#111827;font-size:18px;'>Prazo se aproximando</h2>"
                + "<p style='margin:0 0 24px;color:#6b7280;font-size:14px;'>Uma tarefa no workspace <strong>" + escapeHtml(workspaceName) + "</strong> está próxima do prazo.</p>"
                + "<div style='border:1px solid #e5e7eb;border-radius:6px;padding:20px;margin-bottom:24px;'>"
                + "<p style='margin:0 0 4px;font-size:16px;font-weight:700;color:#111827;'>" + escapeHtml(task.getTitle()) + "</p>"
                + description
                + "<table style='margin-top:16px;border-collapse:collapse;' cellpadding='0' cellspacing='0'>"
                + projectRow
                + "<tr><td style='padding:4px 0;color:#6b7280;font-size:14px;padding-right:24px;'>Prazo</td>"
                + "<td style='padding:4px 0;font-size:14px;font-weight:600;color:#dc2626;'>" + dueDateFormatted + " UTC</td></tr>"
                + "</table>"
                + "</div>"
                + "<a href='" + taskLink + "' style='display:inline-block;background:#4f46e5;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:6px;font-size:14px;font-weight:600;'>Ver tarefa</a>"
                + "</td></tr>"
                + "<tr><td style='padding:16px 32px;background:#f3f4f6;'>"
                + "<p style='margin:0;color:#9ca3af;font-size:12px;'>Você recebeu este email porque existe uma regra de notificação configurada no workspace <em>" + escapeHtml(workspaceName) + "</em>.</p>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
