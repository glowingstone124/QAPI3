package org.qo.services.mail;

public class MailPreset {
    private MailPreset() {}

    public static String registrationConfirmation(String username, String token) {
        return """
                <h1>Quantum Original2 注册确认</h1>
                <p>账号 <strong>%s</strong> 正在使用此 QQ 注册。</p>
                <p>请在 2 小时内使用以下一次性验证码完成确认：</p>
                <p><code>%s</code></p>
                <p>如非本人操作，请忽略此邮件。</p>
                """.formatted(escapeHtml(username), escapeHtml(token));
    }

    public static String passwordResetConfirmation(String username, String token) {
        return """
                <h1>Quantum Original2 密码重置确认</h1>
                <p>收到账号 <strong>%s</strong> 的密码重置请求。</p>
                <p>请在 2 小时内使用以下一次性验证码完成确认：</p>
                <p><code>%s</code></p>
                <p>如非本人操作，请忽略此邮件。</p>
                """.formatted(escapeHtml(username), escapeHtml(token));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
